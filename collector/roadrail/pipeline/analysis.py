"""분석 일 배치: 기준선 재계산 (FR-401) · 백테스트 (FR-404) · 유지보수 (NFR-05)."""
from __future__ import annotations

import datetime as dt
import logging

import pandas as pd

from ..analytics.backtest import DAYS, model_version, run_backtest
from ..analytics.baseline import WEEKS, compute_baseline
from ..core import db
from ..core.config import settings
from ..core.timeutil import now_kst
from ..providers.base import JobContext

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
    bl = compute_baseline(df)
    p = await db.pool()
    async with p.connection() as conn, conn.transaction(), conn.cursor() as cur:
        await cur.execute("DELETE FROM ana.road_baseline")
        await cur.executemany("""
            INSERT INTO ana.road_baseline (corridor_id, direction, dow, slot_idx, p50_sec, p90_sec, n, window_from, window_to)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)""",
            [(r.corridor_id, r.direction, int(r.dow), int(r.slot_idx), int(r.p50_sec), int(r.p90_sec), int(r.n),
              since.date(), now.date()) for r in bl.itertuples()])
    ctx.rows += len(bl)
    ctx.note(f"기준선 {len(bl)}행 (입력 {len(df)}슬롯)")
    return len(bl)


async def backtest_daily(ctx: JobContext) -> int:
    today = now_kst().date()
    tau = settings().forecast_tau_min
    since = dt.datetime.combine(today, dt.time(), now_kst().tzinfo) - dt.timedelta(days=DAYS, weeks=WEEKS)
    df = await load_series(since)
    # 오늘 슬롯도 평가 대상에 포함 (eval_day 끝 = 내일 0시)
    rows, start, end = run_backtest(df, today + dt.timedelta(days=1), tau)
    await db.execute("DELETE FROM ana.forecast_eval WHERE eval_date = %s", (today,))
    await db.executemany("""
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


async def maintenance(ctx: JobContext) -> int:
    created = await ensure_partitions()
    deleted = await db.execute("DELETE FROM ops.api_call WHERE called_at < now() - interval '90 days'")
    old_runs = await db.execute("DELETE FROM ops.job_run WHERE started_at < now() - interval '90 days'")
    ctx.note(f"파티션 {created}개 생성 · 호출 로그 {deleted}건 · 실행 이력 {old_runs}건 삭제")
    return created
