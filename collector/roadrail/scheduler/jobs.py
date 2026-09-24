"""작업 등록 · 실행기.

실행 1회 = Redis 잠금(rr:lock:{job}) → ops.job_run 기록 → 예산 예약 → 본 작업 → 호출 로그 기록
→ 예산 환불·확정 → 잠금 해제. 같은 작업이 겹쳐 실행되지 않고(관리 API 는 잠금을 보고 409),
예산이 모자라면 호출 없이 SKIPPED_QUOTA 로 끝난다.
"""
from __future__ import annotations

import asyncio
import datetime as dt
import logging
import time
import traceback
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

import httpx

from ..core import db, rds
from ..core.config import settings
from ..core.log import log, mask_text
from ..core.timeutil import now_kst
from ..pipeline import analysis, env, holidays, rail, rail_geometry, road, stations
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
    "rail_geometry": JobSpec(rail_geometry.build_rail_links, _const({}), lock_ttl=3600),
    "holiday_sync": JobSpec(holidays.sync_holidays, _const({"KASI": 3})),
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
    status, message, detail = "OK", None, None
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
    except asyncio.CancelledError:
        # 수집기 종료(SIGTERM) — 'OK' 로 남기지 않고 중단으로 기록한 뒤 취소를 그대로 전파
        status, message = "FAILED", ABORTED_MESSAGE
        raise
    except Exception as e:  # noqa: BLE001 — 작업 실패는 기록하고 스케줄러는 계속
        status, message = "FAILED", f"{type(e).__name__}: {e}"[:1000]
        detail = mask_text(traceback.format_exc())
        logger.exception("작업 실패", extra={"fields": {"job": name}})
    finally:
        await ctx.close_budgets()
        if status != "OK":
            detail = run_detail(name, trigger, status, message, detail, ctx)
        ms = int((time.perf_counter() - t0) * 1000)
        try:
            await db.executemany("""
                INSERT INTO ops.api_call (job_name, provider, endpoint, params_masked, http_status, result_code,
                                          latency_ms, rows, error) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)""",
                ctx.api_calls)
            await db.execute("""UPDATE ops.job_run SET finished_at = now(), status = %s, calls = %s, rows = %s, message = %s,
                                  detail = %s WHERE run_id = %s""", (status, ctx.calls, ctx.rows, message, detail, run["run_id"]))
            await db.execute("""UPDATE ops.collect_job SET last_run_at = now(), last_status = %s, last_duration_ms = %s,
                                  last_calls = %s, last_rows = %s, last_message = %s WHERE job_name = %s""",
                             (status, ms, ctx.calls, ctx.rows, message, name))
            await persist_quota(set(ctx.estimates) | set(ctx.allowances))
        finally:
            if await r.get(rds.lock_key(name)) == token:
                await r.delete(rds.lock_key(name))
    log(logger, "작업 종료", job=name, trigger=trigger, status=status, calls=ctx.calls, rows=ctx.rows, ms=ms)
    return status


ABORTED_MESSAGE = "중단됨 — 작업 도중 수집기가 종료·재시작됨"


async def recover_after_restart(providers: list[str]) -> int:
    """상주 수집기 기동 시: 이전 프로세스가 강제 종료되며 남긴 흔적을 정리한다.

    - ops.job_run 의 RUNNING → FAILED(중단) · collect_job.last_status 도 함께
    - rr:lock:* — 이전 프로세스의 잠금이 TTL(최대 2시간) 동안 같은 작업을 막는다
    - 오늘 예산의 쓰지 않은 예약(예약 누계 − 실제 호출) — 환불되지 못한 몫
    수집기는 한 대만 띄운다는 전제(docker-compose). 반환: 정리한 실행 수.
    """
    runs = await db.fetch("""
        UPDATE ops.job_run SET status = 'FAILED', message = %s,
               detail = coalesce(detail, format('작업: %%s · 트리거: %%s · 상태: FAILED\n시작 %%s 이후 수집기가 종료되어 끝을 기록하지 못함',
                                                job_name, trigger, to_char(started_at AT TIME ZONE 'Asia/Seoul', 'MM-DD HH24:MI:SS')))
        WHERE status = 'RUNNING' RETURNING job_name""", (ABORTED_MESSAGE,))
    await db.execute("""UPDATE ops.collect_job SET last_status = 'FAILED', last_message = %s
                        WHERE last_status = 'RUNNING'""", (ABORTED_MESSAGE,))
    await db.execute("UPDATE ops.backfill SET status = 'FAILED', message = %s WHERE status = 'RUNNING'", (ABORTED_MESSAGE,))
    r = rds.client()
    locks = [k async for k in r.scan_iter(match=rds.lock_key("*"))]
    if locks:
        await r.delete(*locks)
    day = now_kst().strftime("%Y%m%d")
    for p in providers:
        total, used = await r.get(rds.quota_key(p, day)), await r.get(f"quota:used:{p}:{day}")
        if total is not None and int(total) > int(used or 0):
            await r.set(rds.quota_key(p, day), int(used or 0), ex=172800)
    if runs or locks:
        log(logger, "이전 실행 정리", runs=len(runs), locks=len(locks))
    return len(runs)


def run_detail(name: str, trigger: str, status: str, message: str | None, trace: str | None, ctx: JobContext) -> str:
    """수집 상태 화면 '오류 상세' 에 그대로 보여 줄 전체 내용 (키 마스킹)."""
    lines = [f"작업: {name} · 트리거: {trigger} · 상태: {status}", f"호출 {ctx.calls}건 · 저장 {ctx.rows}행",
             f"메시지: {message or '-'}"]
    if ctx.notes:
        lines += ["", "[작업 메모]", *ctx.notes]
    failed = [c for c in ctx.api_calls if c[8] or (c[4] is not None and c[4] != 200)]
    if failed:
        lines += ["", f"[실패한 외부 호출 {len(failed)}건]"]
        for c in failed[:50]:
            lines.append(f"{c[1]} {c[2]} · HTTP {c[4]} · 결과코드 {c[5]} · {c[6]}ms · 파라미터 {c[3]}\n  → {c[8]}")
    if trace:
        lines += ["", "[스택 트레이스]", trace]
    return mask_text("\n".join(lines))[:20000]


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
