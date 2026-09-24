"""작업 등록 · 실행기.

실행 1회 = Redis 잠금(rr:lock:{job}) → ops.job_run 기록 → 예산 예약 → 본 작업 → 호출 로그 기록
→ 예산 환불·확정 → 잠금 해제. 같은 작업이 겹쳐 실행되지 않고(관리 API 는 잠금을 보고 409),
예산이 모자라면 호출 없이 SKIPPED_QUOTA 로 끝난다.
"""
from __future__ import annotations

import datetime as dt
import logging
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

import httpx

from ..core import db, rds
from ..core.config import settings
from ..core.log import log
from ..core.timeutil import now_kst
from ..pipeline import analysis, env, rail, road, stations
from ..providers.base import JobContext
from .quota import QuotaBudget, QuotaExhausted

logger = logging.getLogger(__name__)


@dataclass
class JobSpec:
    func: Callable[..., Awaitable[int]]
    estimate: Callable[[], Awaitable[dict[str, int]]]
    lock_ttl: int = 1800


async def _segments_estimate() -> dict[str, int]:
    r = await db.fetchone("SELECT count(DISTINCT (start_unit_code, end_unit_code)) AS n FROM ref.corridor_road")
    return {"EX": int(r["n"] * 1.3) + 2}


async def _grid_estimate() -> dict[str, int]:
    r = await db.fetchone("SELECT count(DISTINCT (nx, ny)) AS n FROM ref.corridor_env_point")
    return {"KMA": r["n"]}


async def _sido_estimate() -> dict[str, int]:
    r = await db.fetchone("SELECT count(DISTINCT sido_name) AS n FROM ref.corridor_env_point")
    return {"AIRKOREA": r["n"]}


async def _kakao_estimate() -> dict[str, int]:
    r = await db.fetchone("SELECT count(DISTINCT (corridor_id, direction)) AS n FROM ref.corridor_road")
    return {"KAKAO": r["n"]}


async def _station_estimate() -> dict[str, int]:
    r = await db.fetchone("SELECT count(*) AS n FROM ref.station WHERE lat IS NULL")
    return {"KAKAO_LOCAL": r["n"]}


def _const(d: dict[str, int]) -> Callable[[], Awaitable[dict[str, int]]]:
    async def f() -> dict[str, int]:
        return d
    return f


JOBS: dict[str, JobSpec] = {
    "toll_unit_sync": JobSpec(road.sync_toll_units, _const({"EX": 8})),
    "road_travel_time": JobSpec(road.collect_travel_time, _segments_estimate, lock_ttl=900),
    "road_gap_backfill": JobSpec(road.backfill_gaps, _const({"EX": 50})),
    "road_volume_all": JobSpec(road.collect_volume, _const({"EX": 1})),
    "road_incident_sms": JobSpec(road.collect_incidents, _const({"EX": 1})),
    "rail_daily": JobSpec(rail.rail_daily, _const({"KORAIL": rail.CALLS_PER_DAY})),
    "weather_vilage": JobSpec(env.collect_weather, _grid_estimate),
    "air_quality_sido": JobSpec(env.collect_air, _sido_estimate),
    "kakao_eta": JobSpec(env.collect_kakao_eta, _kakao_estimate),
    "station_geocode": JobSpec(stations.geocode_stations, _station_estimate),
    "baseline_daily": JobSpec(analysis.baseline_daily, _const({})),
    "backtest_daily": JobSpec(analysis.backtest_daily, _const({}), lock_ttl=3600),
    "maintenance": JobSpec(analysis.maintenance, _const({})),
    # 관리 API 백필 전용 (스케줄 없음)
    "rail_backfill": JobSpec(rail.rail_backfill, _const({}), lock_ttl=7200),
}

_http: httpx.AsyncClient | None = None
_budget: QuotaBudget | None = None


def http() -> httpx.AsyncClient:
    global _http
    if _http is None:
        _http = httpx.AsyncClient(headers={"User-Agent": "roadrail-collector/0.1"}, follow_redirects=True)
    return _http


def budget() -> QuotaBudget:
    global _budget
    if _budget is None:
        _budget = QuotaBudget(rds.client(), settings().quota_limit)
    return _budget


async def run_job(name: str, trigger: str = "SCHEDULE", estimates: dict[str, int] | None = None, **kwargs) -> str:
    spec = JOBS[name]
    r = rds.client()
    token = uuid.uuid4().hex
    if not await r.set(rds.lock_key(name), token, nx=True, ex=spec.lock_ttl):
        log(logger, "이미 실행 중 — 건너뜀", job=name, trigger=trigger)
        return "LOCKED"
    t0 = time.perf_counter()
    run = await db.fetchone("INSERT INTO ops.job_run (job_name, trigger, status) VALUES (%s, %s, 'RUNNING') RETURNING run_id",
                            (name, trigger))
    await db.execute("UPDATE ops.collect_job SET last_status = 'RUNNING' WHERE job_name = %s", (name,))
    ctx = JobContext(job_name=name, http=http(), budget=budget(), trigger=trigger,
                     estimates=estimates if estimates is not None else await spec.estimate())
    status, message = "OK", None
    try:
        await ctx.open_budgets()
        await spec.func(ctx, **kwargs)
        partial = [n for n in ctx.notes if n.startswith("PARTIAL")]
        if partial:
            status, message = "PARTIAL", "; ".join(partial)[:1000]
        else:
            message = "; ".join(ctx.notes)[:1000] or None
    except QuotaExhausted as e:
        status, message = "SKIPPED_QUOTA", str(e)
        log(logger, "예산 부족으로 건너뜀", logging.WARNING, job=name, provider=e.provider, needed=e.needed,
            remaining=e.remaining)
    except Exception as e:  # noqa: BLE001 — 작업 실패는 기록하고 스케줄러는 계속
        status, message = "FAILED", f"{type(e).__name__}: {e}"[:1000]
        logger.exception("작업 실패", extra={"fields": {"job": name}})
    finally:
        await ctx.close_budgets()
        ms = int((time.perf_counter() - t0) * 1000)
        try:
            await db.executemany("""
                INSERT INTO ops.api_call (job_name, provider, endpoint, params_masked, http_status, result_code,
                                          latency_ms, rows, error) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)""",
                ctx.api_calls)
            await db.execute("""UPDATE ops.job_run SET finished_at = now(), status = %s, calls = %s, rows = %s, message = %s
                                WHERE run_id = %s""", (status, ctx.calls, ctx.rows, message, run["run_id"]))
            await db.execute("""UPDATE ops.collect_job SET last_run_at = now(), last_status = %s, last_duration_ms = %s,
                                  last_calls = %s, last_rows = %s, last_message = %s WHERE job_name = %s""",
                             (status, ms, ctx.calls, ctx.rows, message, name))
            await persist_quota(set(ctx.estimates) | set(ctx.allowances))
        finally:
            if await r.get(rds.lock_key(name)) == token:
                await r.delete(rds.lock_key(name))
    log(logger, "작업 종료", job=name, trigger=trigger, status=status, calls=ctx.calls, rows=ctx.rows, ms=ms)
    return status


async def persist_quota(providers: set[str]) -> None:
    """Redis 예산 카운터 → ops.quota_budget 확정값."""
    for p in providers:
        if p not in ("EX", "KORAIL", "KMA", "AIRKOREA", "KAKAO", "KAKAO_LOCAL"):
            continue
        s = await budget().snapshot(p)
        await db.execute("""
            INSERT INTO ops.quota_budget (provider, day, daily_limit, used, reserved, updated_at)
            VALUES (%s, %s, %s, %s, %s, now())
            ON CONFLICT (provider, day) DO UPDATE SET daily_limit = EXCLUDED.daily_limit, used = EXCLUDED.used,
              reserved = EXCLUDED.reserved, updated_at = now()""",
            (p, dt.date.fromisoformat(s["day"]), s["limit"], s["used"], s["reserved"]))


async def run_backfill(backfill_id: str) -> str:
    bf = await db.fetchone("SELECT * FROM ops.backfill WHERE backfill_id = %s", (backfill_id,))
    if not bf:
        return "NOT_FOUND"
    await db.execute("UPDATE ops.backfill SET status = 'RUNNING' WHERE backfill_id = %s", (backfill_id,))
    status = await run_job("rail_backfill", "BACKFILL", estimates={"KORAIL": bf["planned_calls"]},
                           start=bf["from_date"], end=bf["to_date"], backfill_id=backfill_id)
    final = "DONE" if status in ("OK", "PARTIAL") else "FAILED"
    await db.execute("UPDATE ops.backfill SET status = %s, finished_at = now(), message = %s WHERE backfill_id = %s",
                     (final, status, backfill_id))
    return final


def new_id() -> str:
    return now_kst().strftime("%Y%m%d%H%M%S") + uuid.uuid4().hex[:12]
