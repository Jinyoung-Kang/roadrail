"""분석 일 배치: 기준선 재계산 (FR-401) · 백테스트 (FR-404) · 유지보수 (NFR-05)."""
from __future__ import annotations

import asyncio
import datetime as dt
import logging

import pandas as pd

from ..analytics.backtest import DAYS, model_version, run_backtest
from ..analytics.baseline import WEEKS, compute_baseline
from ..analytics.road_quality import despike
from ..analytics.trajectory import RULE as TRAJECTORY_RULE
from ..analytics.trajectory import SegmentSeries, trajectory_sec
from ..core import db
from ..core.config import settings
from ..core.timeutil import KST, now_kst
from ..providers.base import JobContext
from . import holidays
from .road import load_chains

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
    async with db.transaction() as cur:
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
    # 오늘 결과 교체는 한 트랜잭션 — 삽입이 실패하면 이전 결과가 남는다(L9)
    async with db.transaction() as cur:
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


# ---------------------------------------------------------------- 카카오 예측 정답 데이터 (ADR-029 3단계)

EVAL_LOOKBACK = dt.timedelta(days=7)
EVAL_SETTLE = dt.timedelta(hours=8)   # 공개 지연(약 3시간) + 긴 길 소요 — 이보다 최근 출발은 구간 값이 다 오지 않았다


async def kakao_eta_eval(ctx: JobContext, lookback_days: int | None = None) -> int:
    """ana.kakao_eta 의 출발 시각마다 고속도로 구간 값으로 궤적 합(T-v1)을 계산해 ana.kakao_eta_eval 에 둔다. 외부 호출 없음.
    매일은 최근 7일만, 처음 한 번은 lookback_days 로 지난 예측 전체를 채운다(보존 90일 안)."""
    now = now_kst()
    todo = await db.fetch("""
        SELECT k.depart_at, k.corridor_id, k.direction, k.requested_at, k.duration_sec
        FROM ana.kakao_eta k
        WHERE k.depart_at BETWEEN %s AND %s
          AND NOT EXISTS (SELECT 1 FROM ana.kakao_eta_eval e WHERE (e.depart_at, e.corridor_id, e.direction, e.requested_at)
                                                                = (k.depart_at, k.corridor_id, k.direction, k.requested_at))""",
                          (now - (dt.timedelta(days=lookback_days) if lookback_days else EVAL_LOOKBACK), now - EVAL_SETTLE))
    if not todo:
        ctx.note("평가할 카카오 예측 없음")
        return 0
    chains = await load_chains()
    keys = sorted({s.key for (cid, d), segs in chains.items() for s in segs})
    lo = min(r["depart_at"] for r in todo) - dt.timedelta(minutes=45)
    hi = max(r["depart_at"] for r in todo) + dt.timedelta(hours=8)
    obs = await db.fetch("""
        SELECT t.slot_ts, t.start_unit_code, t.end_unit_code, t.travel_sec, t.vehicles FROM ts.road_travel_time t
        JOIN unnest(%s::varchar[], %s::varchar[]) AS k(s, e) ON t.start_unit_code = k.s AND t.end_unit_code = k.e
        WHERE t.slot_ts BETWEEN %s AND %s AND t.car_type = '1' AND t.quality = 'OK'""",
                         ([k[0] for k in keys], [k[1] for k in keys], lo, hi))
    raw: dict[tuple[str, str], dict[dt.datetime, tuple[int, int | None]]] = {}
    for o in obs:
        raw.setdefault((o["start_unit_code"], o["end_unit_code"]), {})[o["slot_ts"].astimezone(KST)] = (o["travel_sec"], o["vehicles"])
    series = {k: SegmentSeries(despike(v)[0]) for k, v in raw.items()}   # 길 합산과 같은 튀는 값 제거(H-v1)
    empty = SegmentSeries({})
    rows, missing = [], 0
    for r in todo:
        segs = chains.get((r["corridor_id"], r["direction"]))
        actual = trajectory_sec([series.get(s.key, empty) for s in segs], r["depart_at"].astimezone(KST)) if segs else None
        if actual is None:
            missing += 1
            continue
        rows.append((r["depart_at"], r["corridor_id"], r["direction"], r["requested_at"], r["duration_sec"], actual, TRAJECTORY_RULE))
    await db.executemany("""
        INSERT INTO ana.kakao_eta_eval (depart_at, corridor_id, direction, requested_at, kakao_sec, actual_sec, rule)
        VALUES (%s, %s, %s, %s, %s, %s, %s) ON CONFLICT DO NOTHING""", rows)
    ctx.rows += len(rows)
    ctx.note(f"카카오 예측 {len(todo)}건 중 정답 {len(rows)}건 (구간 값 부족 {missing}건 — 다음 실행에서 다시)")
    return len(rows)
