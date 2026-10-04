"""분석 일 배치: 기준선 재계산 (FR-401) · 백테스트 (FR-404) · 유지보수 (NFR-05)."""
from __future__ import annotations

import asyncio
import datetime as dt
import logging

import pandas as pd

from ..analytics.backtest import DAYS, model_version, run_backtest
from ..analytics.baseline import WEEKS, compute_baseline
from ..core import db
from ..core.config import settings
from ..core.timeutil import now_kst
from ..providers.base import JobContext
from . import holidays

logger = logging.getLogger(__name__)

PARTITIONED = [("ts.road_travel_time", "ts"), ("ts.road_corridor_tt", "ts"), ("ts.road_volume", "ts"),
               ("env.weather_fcst", "ts"), ("rail.run_info", "date")]


async def load_series(since: dt.datetime, until: dt.datetime | None = None) -> pd.DataFrame:
    rows = await db.fetch("""SELECT corridor_id, direction, slot_ts, travel_sec FROM ts.road_corridor_tt
                             WHERE slot_ts >= %s AND (%s::timestamptz IS NULL OR slot_ts < %s)""", (since, until, until))
    return pd.DataFrame(rows, columns=["corridor_id", "direction", "slot_ts", "travel_sec"])


async def baseline_daily(ctx: JobContext) -> int:
    now = now_kst()
    since = now - dt.timedelta(weeks=WEEKS)
    df = await load_series(since)
    hol = await holidays.holiday_days(since.date())
    bl = await asyncio.to_thread(compute_baseline, df, hol)  # 수 초 걸리는 pandas 계산 — 이벤트 루프를 막지 않게(L9)
    p = await db.pool()
    async with p.connection() as conn, conn.transaction(), conn.cursor() as cur:
        await cur.execute("DELETE FROM ana.road_baseline")
        await cur.executemany("""
            INSERT INTO ana.road_baseline (corridor_id, direction, dow, slot_idx, p50_sec, p90_sec, n, window_from, window_to)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)""",
            [(r.corridor_id, r.direction, int(r.dow), int(r.slot_idx), int(r.p50_sec), int(r.p90_sec), int(r.n),
              since.date(), now.date()) for r in bl.itertuples()])
    ctx.rows += len(bl)
    ctx.note(f"기준선 {len(bl)}행 (입력 {len(df)}슬롯, 공휴일 {sum(1 for h in hol if h <= now.date())}일 제외)")
    return len(bl)


async def backtest_daily(ctx: JobContext) -> int:
    today = now_kst().date()
    tau = settings().forecast_tau_min
    since = dt.datetime.combine(today, dt.time(), now_kst().tzinfo) - dt.timedelta(days=DAYS, weeks=WEEKS)
    df = await load_series(since)
    # 오늘 슬롯도 평가 대상에 포함 (eval_day 끝 = 내일 0시)
    hol = await holidays.holiday_days(since.date())  # 운영과 같은 기준선(공휴일 제외)
    # 백테스트는 pandas 로 십여 초 걸린다(실측 14.2초) — 그동안 다른 수집 작업이 멈추지 않게 스레드에서(L9)
    rows, start, end = await asyncio.to_thread(run_backtest, df, today + dt.timedelta(days=1), tau, holidays=hol)
    p = await db.pool()
    # 오늘 결과 교체는 한 트랜잭션 — 삽입이 실패하면 이전 결과가 남는다(L9)
    async with p.connection() as conn, conn.transaction(), conn.cursor() as cur:
        await cur.execute("DELETE FROM ana.forecast_eval WHERE eval_date = %s", (today,))
        await cur.executemany("""
            INSERT INTO ana.forecast_eval (eval_date, model, corridor_id, direction, horizon_min, mae_sec, mape, n,
                                           window_from, window_to, model_version)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)""",
            [(today, r.model, r.corridor_id, r.direction, r.horizon_min, r.mae_sec, r.mape, r.n, start, end,
              model_version(tau)) for r in rows])
    ctx.rows += len(rows)
    ctx.note(f"백테스트 {len(rows)}행 (입력 {len(df)}슬롯, 창 {start:%m-%d}~{end:%m-%d})")
    return len(rows)


async def ensure_partitions(months_ahead: int = 2) -> int:
    today = now_kst().date().replace(day=1)
    n = 0
    for table, kind in PARTITIONED:
        fn = "ops.ensure_month_partitions_date" if kind == "date" else "ops.ensure_month_partitions"
        r = await db.fetchone(f"SELECT {fn}(%s, %s, %s) AS n", (table, today, months_ahead + 1))
        n += r["n"]
    return n


# 보존 기간 — 사용자 결정(2026-10-04, ADR-024): 계속 쌓이는 기록은 90일. (표, 기준 시각 열) — 돌발은 마지막으로 본 시각(안내가 끝난 지 90일)
RETENTION_DAYS = 90
RETENTION = [("ts.road_incident", "last_seen_at", "돌발"), ("ops.slot_gap", "slot_ts", "결측"), ("env.air_quality", "data_time", "대기질"),
             ("ana.kakao_eta", "requested_at", "카카오 ETA"), ("ops.api_call", "called_at", "호출 로그"),
             ("ops.job_run", "started_at", "실행 이력")]


async def retention(ctx: JobContext) -> int:
    """보존 기간이 지난 행 삭제 (매일 — 노트북이 잠들어 건너뛰면 기동 때 한 번)."""
    counts = []
    for table, col, label in RETENTION:
        n = await db.execute(f"DELETE FROM {table} WHERE {col} < now() - make_interval(days => %s)", (RETENTION_DAYS,))
        counts.append(f"{label} {n}")
    ctx.note(f"{RETENTION_DAYS}일 보존 — 삭제: " + " · ".join(counts))
    return 0


async def maintenance(ctx: JobContext) -> int:
    created = await ensure_partitions()
    deleted = await db.execute("DELETE FROM ops.api_call WHERE called_at < now() - interval '90 days'")
    old_runs = await db.execute("DELETE FROM ops.job_run WHERE started_at < now() - interval '90 days'")
    ctx.note(f"파티션 {created}개 생성 · 호출 로그 {deleted}건 · 실행 이력 {old_runs}건 삭제")
    return created
