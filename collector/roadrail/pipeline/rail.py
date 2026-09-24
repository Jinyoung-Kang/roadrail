"""철도 일 배치 (FR-204, FR-301~303).

하루치 전국 운행계획(≈900행) · 운행정보(≈10,500행)를 받아 영구 보관하고(ADR-003),
열차 단위 정시성(P-v1)과 코리도 구간 운행(P-i1)을 계산한다. 하루 호출 수 ≈ 3건.
"""
from __future__ import annotations

import datetime as dt
import logging
from collections import defaultdict

from ..analytics.punctuality import RULE_CORRIDOR, RULE_EXACT, corridor_trip, train_punctuality
from ..core import db
from ..core.config import settings
from ..core.timeutil import now_kst
from ..providers import korail
from ..providers.base import JobContext

logger = logging.getLogger(__name__)

EARLIEST_DAYS = 92   # 운행정보 조회 가능 기간 ≈ 3개월 전 (실측 2026-06-20 ~ 어제)
CALLS_PER_DAY = 3    # 계획 1 + 운행정보 2 (10,000행/페이지)


def earliest_day(today: dt.date) -> dt.date:
    return today - dt.timedelta(days=EARLIEST_DAYS)


async def rail_day(ctx: JobContext, day: dt.date) -> int:
    plan = await korail.fetch_plan(ctx, day)
    info = await korail.fetch_info(ctx, day)
    plan = list({(p["run_ymd"], p["trn_no"]): p for p in plan}.values())
    info = list({(i["run_ymd"], i["trn_no"], i["run_seq"]): i for i in info}.values())

    await db.executemany("""
        INSERT INTO rail.run_plan (run_ymd, trn_no, dep_stn_cd, arr_stn_cd, plan_dep_at, plan_arr_at, fetched_at)
        VALUES (%s, %s, %s, %s, %s, %s, now())
        ON CONFLICT (run_ymd, trn_no) DO UPDATE SET dep_stn_cd = EXCLUDED.dep_stn_cd, arr_stn_cd = EXCLUDED.arr_stn_cd,
          plan_dep_at = EXCLUDED.plan_dep_at, plan_arr_at = EXCLUDED.plan_arr_at, fetched_at = now()""",
        [(p["run_ymd"], p["trn_no"], p["dep_stn_cd"], p["arr_stn_cd"], p["plan_dep_at"], p["plan_arr_at"]) for p in plan])
    await db.executemany("""
        INSERT INTO rail.run_info (run_ymd, trn_no, run_seq, stn_cd, stn_nm, line_cd, line_nm, updown_cd, stop_type_cd,
                                   arr_at, dep_at, fetched_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())
        ON CONFLICT (run_ymd, trn_no, run_seq) DO UPDATE SET stn_cd = EXCLUDED.stn_cd, arr_at = EXCLUDED.arr_at,
          dep_at = EXCLUDED.dep_at, stop_type_cd = EXCLUDED.stop_type_cd, fetched_at = now()""",
        [(i["run_ymd"], i["trn_no"], i["run_seq"], i["stn_cd"], i["stn_nm"], i["line_cd"], i["line_nm"],
          i["updown_cd"], i["stop_type_cd"], i["arr_at"], i["dep_at"]) for i in info])
    stations = {i["stn_cd"]: i["stn_nm"] for i in info if i.get("stn_nm")}
    await db.executemany("""INSERT INTO ref.station (stn_cd, stn_nm, source) VALUES (%s, %s, 'RUNINFO')
                            ON CONFLICT (stn_cd) DO NOTHING""", list(stations.items()))
    ctx.rows += len(plan) + len(info)
    n = await compute_day(day, plan, info)
    ctx.note(f"{day} 계획 {len(plan)} · 운행 {len(info)} · 정시성 {n}")
    return len(plan) + len(info)


async def compute_day(day: dt.date, plan: list[dict] | None = None, info: list[dict] | None = None) -> int:
    """이미 저장된(또는 넘겨받은) 하루치로 정시성 · 코리도 운행을 다시 계산 (재실행 멱등)."""
    thr = settings().on_time_threshold_min
    if plan is None:
        plan = await db.fetch("SELECT * FROM rail.run_plan WHERE run_ymd = %s", (day,))
    if info is None:
        info = await db.fetch("SELECT * FROM rail.run_info WHERE run_ymd = %s", (day,))
    stops: dict[str, list[dict]] = defaultdict(list)
    for i in info:
        stops[i["trn_no"]].append(i)
    for v in stops.values():
        v.sort(key=lambda s: s["run_seq"])

    tps = {p["trn_no"]: train_punctuality(p, stops.get(p["trn_no"], []), thr) for p in plan}
    await db.executemany("""
        INSERT INTO rail.train_punctuality (run_ymd, trn_no, dep_stn_cd, arr_stn_cd, plan_dep_at, plan_arr_at,
          act_dep_at, act_arr_at, dep_delay_min, arr_delay_min, on_time, status, calc_rule, threshold_min, computed_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())
        ON CONFLICT (run_ymd, trn_no) DO UPDATE SET act_dep_at = EXCLUDED.act_dep_at, act_arr_at = EXCLUDED.act_arr_at,
          dep_delay_min = EXCLUDED.dep_delay_min, arr_delay_min = EXCLUDED.arr_delay_min, on_time = EXCLUDED.on_time,
          status = EXCLUDED.status, calc_rule = EXCLUDED.calc_rule, threshold_min = EXCLUDED.threshold_min,
          computed_at = now()""",
        [(t.run_ymd, t.trn_no, t.dep_stn_cd, t.arr_stn_cd, t.plan_dep_at, t.plan_arr_at, t.act_dep_at, t.act_arr_at,
          t.dep_delay_min, t.arr_delay_min, t.on_time, t.status, RULE_EXACT, thr) for t in tps.values()])

    pairs = await db.fetch("SELECT corridor_id, direction, dep_stn_cd, arr_stn_cd FROM ref.corridor_rail")
    trips = []
    for pr in pairs:
        for trn, st in stops.items():
            ct = corridor_trip(st, pr["dep_stn_cd"], pr["arr_stn_cd"], tps.get(trn), thr)
            if ct:
                trips.append((ct.run_ymd, pr["corridor_id"], pr["direction"], ct.trn_no, ct.act_dep_at, ct.act_arr_at,
                              ct.est_plan_dep_at, ct.est_plan_arr_at, ct.dep_delay_min, ct.arr_delay_min,
                              ct.dep_basis, ct.arr_basis, ct.ride_min, ct.on_time, RULE_CORRIDOR))
    await db.execute("DELETE FROM rail.corridor_trip WHERE run_ymd = %s", (day,))
    await db.executemany("""
        INSERT INTO rail.corridor_trip (run_ymd, corridor_id, direction, trn_no, act_dep_at, act_arr_at,
          est_plan_dep_at, est_plan_arr_at, dep_delay_min, arr_delay_min, dep_basis, arr_basis, ride_min, on_time, calc_rule)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)""", trips)
    return len(tps)


async def rail_daily(ctx: JobContext) -> int:
    """전일 + 누락된 최근 날짜(최대 7일)를 받는다 — 실패한 날은 다음 날 누적 재시도 (FR-204)."""
    today = now_kst().date()
    have = {r["run_ymd"] for r in await db.fetch(
        "SELECT DISTINCT run_ymd FROM rail.run_plan WHERE run_ymd >= %s", (today - dt.timedelta(days=7),))}
    days = [today - dt.timedelta(days=k) for k in range(7, 0, -1)]
    todo = [d for d in days if d not in have or d == today - dt.timedelta(days=1)]
    total = 0
    for d in todo:
        total += await rail_day(ctx, d)
    return total


async def rail_backfill(ctx: JobContext, start: dt.date, end: dt.date, backfill_id: str | None = None) -> int:
    today = now_kst().date()
    start = max(start, earliest_day(today))
    end = min(end, today - dt.timedelta(days=1))
    total, d, done = 0, start, 0
    while d <= end:
        total += await rail_day(ctx, d)
        done += 1
        if backfill_id:
            await db.execute("UPDATE ops.backfill SET done_days = %s WHERE backfill_id = %s", (done, backfill_id))
        d += dt.timedelta(days=1)
    return total
