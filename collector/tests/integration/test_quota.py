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
