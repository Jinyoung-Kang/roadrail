"""QuotaBudget 동시성 (10장 '예산'): 동시 작업 20개가 한도 500 을 넘지 않는다."""
import asyncio

from roadrail.core import rds
from roadrail.scheduler.quota import QuotaBudget, QuotaExhausted


async def test_concurrent_reservations_never_exceed_limit():
    budget = QuotaBudget(rds.client(), lambda p: 500)
    results = await asyncio.gather(*(budget.reserve("AIRKOREA", 30) for _ in range(20)))
    assert sum(results) == 16  # 16 × 30 = 480, 17번째는 510 이 되어 거절
    snap = await budget.snapshot("AIRKOREA")
    assert snap["reserved"] == 480 and snap["remaining"] == 20


async def test_allowance_consume_and_refund():
    budget = QuotaBudget(rds.client(), lambda p: 10)
    a = await budget.allowance("KMA", 6)
    for _ in range(4):
        await a.take()
    await a.close()  # 쓰지 않은 2건 환불
    snap = await budget.snapshot("KMA")
    assert snap["used"] == 4 and snap["reserved"] == 0 and snap["remaining"] == 6


async def test_allowance_grows_one_by_one_until_limit():
    budget = QuotaBudget(rds.client(), lambda p: 3)
    a = await budget.allowance("EX", 1)
    await a.take()
    await a.take()
    await a.take()
    try:
        await a.take()
        raise AssertionError("한도 초과가 허용됨")
    except QuotaExhausted as e:
        assert e.provider == "EX" and e.remaining == 0
    assert (await budget.snapshot("EX"))["used"] == 3


async def test_allowance_refuses_when_estimate_does_not_fit():
    budget = QuotaBudget(rds.client(), lambda p: 5)
    assert await budget.reserve("KAKAO", 4)
    try:
        await budget.allowance("KAKAO", 2)
        raise AssertionError("예약이 허용됨")
    except QuotaExhausted as e:
        assert e.needed == 2 and e.remaining == 1


async def test_budget_survives_a_redis_restart():
    # M1: Redis 는 저장을 꺼 두어(compose) 다시 띄우면 그날 예산 카운터가 0 이 됐다 → 한도의 두 배까지 호출 가능.
    # 기동 정리 때 ops.quota_budget 의 확정값을 되돌려 놓는다.
    from roadrail.core import db
    from roadrail.core.timeutil import now_kst
    from roadrail.scheduler import jobs
    limit = 100
    jobs._budget = QuotaBudget(rds.client(), lambda p: limit)
    await db.execute("DELETE FROM ops.quota_budget")
    await db.execute("INSERT INTO ops.quota_budget (provider, day, daily_limit, used, reserved) VALUES ('EX', %s, %s, %s, 0)",
                     (now_kst().date(), limit, limit - 5))
    await jobs.recover_after_restart(["EX"])               # Redis 는 비어 있다(재시작 직후)
    assert not await jobs.budget().reserve("EX", 10)       # 남은 몫은 5
    assert await jobs.budget().reserve("EX", 5)
    jobs._budget = None


async def test_quota_snapshot_never_goes_down_within_a_day():
    # Redis 만 다시 뜬 경우에도 작업 뒤 동기화가 DB 의 큰 값을 지키고 Redis 를 되돌린다
    from roadrail.core import db
    from roadrail.core.timeutil import now_kst
    from roadrail.scheduler import jobs
    jobs._budget = QuotaBudget(rds.client(), lambda p: 100)
    await db.execute("DELETE FROM ops.quota_budget")
    await db.execute("INSERT INTO ops.quota_budget (provider, day, daily_limit, used, reserved) VALUES ('KMA', %s, 100, 40, 0)",
                     (now_kst().date(),))
    a = await jobs.budget().allowance("KMA", 3)             # 재시작 뒤 새로 쓴 3건
    for _ in range(3):
        await a.take()
    await a.close()
    await jobs.sync_quota(["KMA"])
    row = await db.fetchone("SELECT used FROM ops.quota_budget WHERE provider = 'KMA'")
    assert row["used"] == 43 and (await jobs.budget().snapshot("KMA"))["used"] == 43
    jobs._budget = None


async def test_allowance_books_everything_on_the_day_it_was_reserved():
    # L3: 예약이 자정을 넘기면 환불 · 추가 예약 · 호출 기록이 다음 날 카운터로 가서, 전날 예약은 영영 환불되지 않고
    # 다음 날의 다른 작업 예약을 깎았다
    import datetime as dt

    from roadrail.core.timeutil import KST
    clock = {"now": dt.datetime(2026, 10, 3, 23, 59, 50, tzinfo=KST)}
    budget = QuotaBudget(rds.client(), lambda p: 100, clock=lambda: clock["now"])
    a = await budget.allowance("EX", 10)
    await a.take()
    await a.take()
    clock["now"] = dt.datetime(2026, 10, 4, 0, 0, 5, tzinfo=KST)
    assert await budget.reserve("EX", 5)                  # 다음 날 다른 작업의 예약
    await a.take()
    await a.close()
    r = rds.client()
    assert int(await r.get("quota:EX:20261003")) == 3     # 전날: 예약 10 중 쓴 3만 남김
    assert int(await r.get("quota:EX:20261004")) == 5     # 다음 날: 다른 작업의 예약 그대로
