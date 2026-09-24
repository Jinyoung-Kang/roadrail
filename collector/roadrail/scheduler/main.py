"""수집기 상주 프로세스: APScheduler(cron, KST) + 명령 스트림 소비 + heartbeat."""
from __future__ import annotations

import asyncio
import datetime as dt
import logging
import signal

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from redis.exceptions import ResponseError

from ..core import db, rds
from ..core.config import settings
from ..core.log import log, setup_logging
from ..core.timeutil import KST, now_kst
from ..pipeline import rail
from ..pipeline.analysis import ensure_partitions
from ..pipeline.seed import apply_seed
from ..providers.base import CONCURRENCY
from .jobs import JOBS, new_id, recover_after_restart, run_backfill, run_job

logger = logging.getLogger(__name__)
_tasks: set[asyncio.Task] = set()


def spawn(coro) -> None:
    t = asyncio.create_task(coro)
    _tasks.add(t)
    t.add_done_callback(_tasks.discard)


async def wait_for_schema(timeout_s: int = 300) -> None:
    """마이그레이션은 api(Flyway)가 소유한다. 스키마가 생길 때까지 기다린다."""
    deadline = asyncio.get_running_loop().time() + timeout_s
    while True:
        try:
            if await db.fetchone("SELECT to_regclass('ops.collect_job') IS NOT NULL AS ok WHERE to_regclass('ops.collect_job') IS NOT NULL"):
                return
        except Exception as e:  # noqa: BLE001
            log(logger, "DB 대기", error=str(e)[:120])
        if asyncio.get_running_loop().time() > deadline:
            raise RuntimeError("스키마(ops.collect_job)가 준비되지 않았습니다 — api 컨테이너(Flyway) 로그를 확인하세요")
        await asyncio.sleep(3)


async def heartbeat() -> None:
    r = rds.client()
    while True:
        await r.set(rds.HEARTBEAT, now_kst().isoformat(), ex=90)
        await asyncio.sleep(30)


async def consume_commands() -> None:
    """관리 API → Redis Stream rr:commands → 작업 실행. 작업이 **끝난 뒤** XACK (at-least-once).
    기동 시 한 번만 미처리(pending) 메시지를 다시 처리한다 — 실행 중 재기동으로 잃은 명령 복구.
    (매 반복마다 pending 을 읽으면 아직 실행 중인 명령이 다시 배달되어 중복 실행된다.)"""
    r = rds.client()
    consumer = "collector-1"
    try:
        await r.xgroup_create(rds.COMMANDS, rds.COMMAND_GROUP, id="$", mkstream=True)
    except ResponseError as e:
        if "BUSYGROUP" not in str(e):
            raise
    stream_id = "0"
    while True:
        try:
            resp = await r.xreadgroup(rds.COMMAND_GROUP, consumer, {rds.COMMANDS: stream_id}, count=10,
                                      block=None if stream_id == "0" else 5000)
            messages = [m for _, ms in resp or [] for m in ms]
            for msg_id, fields in messages:
                spawn(handle_and_ack(msg_id, fields))
            if stream_id == "0" and not messages:
                stream_id = ">"  # pending 을 다 넘겼으면 새 메시지만
            elif stream_id == "0":
                stream_id = messages[-1][0]  # 다음 pending 묶음
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            logger.exception("명령 처리 오류")
            await asyncio.sleep(3)


async def handle_and_ack(msg_id: str, fields: dict) -> None:
    try:
        await handle_command(fields)
    finally:
        await rds.client().xack(rds.COMMANDS, rds.COMMAND_GROUP, msg_id)


async def handle_command(fields: dict) -> None:
    kind = fields.get("type")
    log(logger, "명령 수신", **fields)
    if kind == "run_job" and fields.get("job") in JOBS:
        await run_job(fields["job"], "ADMIN")
    elif kind == "backfill" and fields.get("backfillId"):
        await run_backfill(fields["backfillId"])
    else:
        log(logger, "알 수 없는 명령", logging.WARNING, **fields)


async def schedule_jobs(sched: AsyncIOScheduler) -> int:
    rows = await db.fetch("SELECT job_name, cron FROM ops.collect_job WHERE enabled")
    n = 0
    for r in rows:
        if r["job_name"] not in JOBS:
            continue
        sched.add_job(run_job, CronTrigger.from_crontab(r["cron"], timezone=KST), args=[r["job_name"]],
                      id=r["job_name"], max_instances=1, coalesce=True, misfire_grace_time=120)
        n += 1
    return n


async def startup_kick() -> None:
    """빈 DB 에서 첫 화면이 채워지도록 기동 직후 한 번씩 실행 (FR-702)."""
    await run_job("toll_unit_sync", "STARTUP")
    for job in ("road_travel_time", "road_incident_sms", "road_volume_all", "weather_vilage", "air_quality_sido",
                "kakao_eta"):
        spawn(run_job(job, "STARTUP"))
    has_rail = await db.fetchone("SELECT 1 AS x FROM rail.run_plan LIMIT 1")
    if not has_rail:
        # 철도 운행정보는 약 3개월 전까지만 조회되므로 첫 기동에 전체 기간을 받아 둔다 (≈ 280 호출)
        today = now_kst().date()
        start, end = rail.earliest_day(today), today - dt.timedelta(days=1)
        planned = ((end - start).days + 1) * rail.CALLS_PER_DAY
        bid = new_id()
        await db.execute("""INSERT INTO ops.backfill (backfill_id, provider, job_name, from_date, to_date, planned_calls)
                            VALUES (%s, 'KORAIL', 'rail_daily', %s, %s, %s)""", (bid, start, end, planned))
        log(logger, "초기 철도 백필", from_=str(start), to=str(end), planned=planned)
        await run_backfill(bid)
    await run_job("station_geocode", "STARTUP")  # 새로 나온 역만 (없으면 호출 0)
    if not await db.fetchone("SELECT 1 AS x FROM ref.rail_link LIMIT 1"):
        spawn(run_job("rail_geometry", "STARTUP"))  # 선로 경로가 없으면 한 번 (OSM, 수 분)
    await run_job("baseline_daily", "STARTUP")
    await run_job("backtest_daily", "STARTUP")


async def main() -> None:
    setup_logging()
    s = settings()
    await wait_for_schema()
    await apply_seed()
    await ensure_partitions()
    await recover_after_restart(list(CONCURRENCY))
    sched = AsyncIOScheduler(timezone=KST)
    n = await schedule_jobs(sched) if s.scheduler_enabled else 0
    sched.start()
    log(logger, "수집기 시작", scheduled_jobs=n, scheduler_enabled=s.scheduler_enabled)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    spawn(heartbeat())
    spawn(consume_commands())
    if s.scheduler_enabled:
        spawn(startup_kick())
    await stop.wait()
    sched.shutdown(wait=False)
    for t in list(_tasks):
        t.cancel()
    # 취소된 작업이 '중단' 기록과 잠금 해제를 마칠 시간을 준다 (docker stop 유예 10초 안에서)
    if _tasks:
        await asyncio.wait(list(_tasks), timeout=7)
    await db.close()
    await rds.close()


def run() -> None:
    asyncio.run(main())
