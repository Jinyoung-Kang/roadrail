"""도로 수집 파이프라인 (FR-201~203, FR-405).

영업소 간 통행시간 API 는 오늘 하루치를 차종 → 시각 순으로 정렬해 99행씩 준다.
그래서 구간마다 '지금까지 본 1종 행 수'(Redis ex:tail:*) 를 기억해 **꼬리 페이지만** 다시 받는다.
(보통 구간당 1회 호출. 기획서 가정인 '5분 슬롯 × 호출 1회' 대신 '10분마다 꼬리 1~2페이지'.)
받은 슬롯 범위만 길 합산을 다시 계산하므로 늦게 공개된 값도 자연스럽게 반영된다(멱등 UPSERT).
"""
from __future__ import annotations

import asyncio
import datetime as dt
import logging
import re
from collections import defaultdict

from ..analytics.road_quality import Segment, aggregate_corridor, classify, despike
from ..core import db, rds
from ..core.config import settings
from ..core.timeutil import KST, day_start, now_kst, slots_between
from ..providers import ex
from ..providers.base import JobContext, ProviderError

logger = logging.getLogger(__name__)

JOB = "road_travel_time"

SQL_UPSERT_TT = """
INSERT INTO ts.road_travel_time (slot_ts, start_unit_code, end_unit_code, car_type, travel_sec, min_sec, max_sec,
                                 vehicles, quality, collected_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, now())
ON CONFLICT (slot_ts, start_unit_code, end_unit_code, car_type) DO UPDATE SET
  travel_sec = EXCLUDED.travel_sec, min_sec = EXCLUDED.min_sec, max_sec = EXCLUDED.max_sec,
  vehicles = EXCLUDED.vehicles, quality = EXCLUDED.quality
"""  # collected_at = 처음 저장한 시각 (갱신하지 않음) → 공개 지연 = collected_at − slot_ts
SQL_UPSERT_CORR = """
INSERT INTO ts.road_corridor_tt (slot_ts, corridor_id, direction, travel_sec, observed_segs, total_segs, quality, computed_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, now())
ON CONFLICT (slot_ts, corridor_id, direction) DO UPDATE SET travel_sec = EXCLUDED.travel_sec,
  observed_segs = EXCLUDED.observed_segs, total_segs = EXCLUDED.total_segs, quality = EXCLUDED.quality,
  computed_at = now()
"""


async def load_chains() -> dict[tuple[str, str], list[Segment]]:
    rows = await db.fetch("""
        SELECT r.corridor_id, r.direction, r.start_unit_code, r.end_unit_code, r.distance_km
        FROM ref.corridor_road r JOIN ref.corridor c USING (corridor_id)
        WHERE c.active ORDER BY r.corridor_id, r.direction, r.seq""")
    chains: dict[tuple[str, str], list[Segment]] = defaultdict(list)
    for r in rows:
        chains[(r["corridor_id"], r["direction"])].append(
            Segment(r["start_unit_code"], r["end_unit_code"], float(r["distance_km"])))
    return dict(chains)


def unique_segments(chains: dict[tuple[str, str], list[Segment]]) -> dict[tuple[str, str], Segment]:
    return {s.key: s for segs in chains.values() for s in segs}


async def fetch_segment(ctx: JobContext, seg: Segment, full: bool) -> list[tuple]:
    """꼬리(또는 전체) 페이지를 받아 저장할 행 목록을 돌려준다."""
    s = settings()
    r = rds.client()
    today = now_kst().strftime("%Y%m%d")
    key = rds.tail_key(seg.start, seg.end, today)
    seen = 0 if full else int(await r.get(key) or 0)
    page = seen // ex.PAGE + 1
    out: list[tuple] = []
    while True:
        body = await ex.travel_page(ctx, seg.start, seg.end, page)
        rows, n_type, page_size, more = ex.parse_travel_page(body)
        for t in rows:
            q = classify(seg.distance_km, t.avg_sec, t.vehicles, t.min_sec, hard_min_speed=s.q_hard_min_speed_kmh,
                         max_speed=s.q_max_speed_kmh, free_min_speed=s.q_free_min_speed_kmh, mix_ratio=s.q_mix_ratio)
            out.append((t.slot_ts, t.start, t.end, t.car_type, t.avg_sec, t.min_sec, t.max_sec, t.vehicles, q))
        if n_type:
            seen = (page - 1) * ex.PAGE + n_type
        if not more:
            break
        page += 1
    await r.set(key, seen, ex=172800)
    return out


async def collect_travel_time(ctx: JobContext, full: bool = False, only: set[tuple[str, str]] | None = None) -> int:
    chains = await load_chains()
    segs = unique_segments(chains)
    if only is not None:
        segs = {k: v for k, v in segs.items() if k in only}
    results = await asyncio.gather(*(fetch_segment(ctx, seg, full) for seg in segs.values()), return_exceptions=True)
    rows: list[tuple] = []
    failed = 0
    for seg, res in zip(segs.values(), results, strict=True):
        if isinstance(res, Exception):
            failed += 1
            ctx.note(f"구간 {seg.start}->{seg.end} 실패: {res}")
            if not isinstance(res, ProviderError):
                raise res
            continue
        rows.extend(res)
    await db.executemany(SQL_UPSERT_TT, rows)
    ctx.rows += len(rows)
    if failed:
        ctx.notes.append(f"PARTIAL: 구간 {failed}/{len(segs)} 실패")
    if rows:
        tmin = min(r[0] for r in rows)
        tmax = max(r[0] for r in rows)
        touched = {(r[1], r[2]) for r in rows}
        await recompute_corridors(chains, tmin, tmax, touched)
    await sweep_gaps(now_kst().date())
    return len(rows)


async def recompute_corridors(chains: dict[tuple[str, str], list[Segment]], tmin: dt.datetime, tmax: dt.datetime,
                              touched: set[tuple[str, str]] | None = None) -> int:
    """[tmin, tmax] 슬롯의 길 합산을 다시 계산 (구간이 바뀐 길만)."""
    s = settings()
    affected = {k: v for k, v in chains.items() if touched is None or any(seg.key in touched for seg in v)}
    if not affected:
        return 0
    all_keys = sorted({seg.key for v in affected.values() for seg in v})
    starts = [k[0] for k in all_keys]
    ends = [k[1] for k in all_keys]
    lo, hi = tmin - dt.timedelta(minutes=40), tmax + dt.timedelta(minutes=40)  # 튀는 값 판정용 ±30분 여유
    obs = await db.fetch("""
        SELECT t.slot_ts, t.start_unit_code, t.end_unit_code, t.travel_sec, t.vehicles
        FROM ts.road_travel_time t
        JOIN unnest(%s::varchar[], %s::varchar[]) AS k(s, e) ON t.start_unit_code = k.s AND t.end_unit_code = k.e
        WHERE t.slot_ts BETWEEN %s AND %s AND t.car_type = '1' AND t.quality = 'OK'""", (starts, ends, lo, hi))
    med = await db.fetch("""
        SELECT t.start_unit_code, t.end_unit_code, percentile_cont(0.5) WITHIN GROUP (ORDER BY t.travel_sec) AS p50
        FROM ts.road_travel_time t
        JOIN unnest(%s::varchar[], %s::varchar[]) AS k(s, e) ON t.start_unit_code = k.s AND t.end_unit_code = k.e
        WHERE t.slot_ts BETWEEN %s AND %s AND t.car_type = '1' AND t.quality = 'OK'
        GROUP BY 1, 2""", (starts, ends, tmax - dt.timedelta(hours=24), hi))
    raw: dict[tuple[str, str], dict[dt.datetime, tuple[int, int | None]]] = defaultdict(dict)
    latest: dict[tuple[str, str], dt.datetime] = {}
    for o in obs:
        k = (o["start_unit_code"], o["end_unit_code"])
        ts = o["slot_ts"].astimezone(KST)
        raw[k][ts] = (o["travel_sec"], o["vehicles"])
        latest[k] = max(latest.get(k, ts), ts)
    ok_values = {k: despike(v)[0] for k, v in raw.items()}
    seg_median = {(m["start_unit_code"], m["end_unit_code"]): int(m["p50"]) for m in med}

    out_rows, gaps, resolved = [], [], []
    for (cid, direction), segs in affected.items():
        # 이 길의 공개 워터마크 = 구간 최신 슬롯 중 최댓값 (그 이후는 아직 공개 전)
        wm = max((latest[s.key] for s in segs if s.key in latest), default=None)
        if wm is None:
            continue
        slots = slots_between(tmin, min(tmax, wm))
        stored, missing = aggregate_corridor(segs, slots, ok_values, seg_median, s.corridor_min_observed_ratio)
        for c in stored:
            out_rows.append((c.slot_ts, cid, direction, c.travel_sec, c.observed, c.total, c.quality))
            resolved.append((f"{cid}:{direction}", c.slot_ts))
        for t in missing:
            gaps.append((JOB, f"{cid}:{direction}", t, "LOW_COVERAGE"))
    await db.executemany(SQL_UPSERT_CORR, out_rows)
    await db.executemany("""INSERT INTO ops.slot_gap (job_name, series_key, slot_ts, reason) VALUES (%s, %s, %s, %s)
                            ON CONFLICT DO NOTHING""", gaps)
    await db.executemany(f"""UPDATE ops.slot_gap SET backfilled_at = now()
                             WHERE job_name = '{JOB}' AND series_key = %s AND slot_ts = %s AND backfilled_at IS NULL""",
                         resolved)
    return len(out_rows)


async def sweep_gaps(day: dt.date) -> int:
    """하루 시작 ~ 길 워터마크 사이에 아예 없는 슬롯을 결측으로 기록 (FR-203)."""
    start = day_start(day)
    return await db.execute("""
        INSERT INTO ops.slot_gap (job_name, series_key, slot_ts, reason)
        SELECT %(job)s, c.corridor_id || ':' || c.direction, g.t, 'NO_DATA'
        FROM (SELECT corridor_id, direction, max(slot_ts) AS wm FROM ts.road_corridor_tt
              WHERE slot_ts >= %(start)s AND slot_ts < %(end)s GROUP BY 1, 2) c
        CROSS JOIN LATERAL generate_series(%(start)s::timestamptz, c.wm, interval '5 minutes') AS g(t)
        WHERE NOT EXISTS (SELECT 1 FROM ts.road_corridor_tt x
                          WHERE x.slot_ts = g.t AND x.corridor_id = c.corridor_id AND x.direction = c.direction)
        ON CONFLICT DO NOTHING""", {"job": JOB, "start": start, "end": start + dt.timedelta(days=1)})


async def backfill_gaps(ctx: JobContext) -> int:
    """오늘 열린 결측 → 해당 길 구간을 처음부터 다시 받아 재합산. 지난 날짜는 API 가 주지 않으므로 SOURCE_EXPIRED."""
    today = now_kst().date()
    start = day_start(today)
    await db.execute("""UPDATE ops.slot_gap SET reason = 'SOURCE_EXPIRED'
                        WHERE job_name = %s AND backfilled_at IS NULL AND slot_ts < %s AND reason <> 'SOURCE_EXPIRED'""",
                     (JOB, start))
    open_gaps = await db.fetch("""SELECT DISTINCT series_key FROM ops.slot_gap
                                  WHERE job_name = %s AND backfilled_at IS NULL AND slot_ts >= %s AND attempts < 6""",
                               (JOB, start))
    if not open_gaps:
        return 0
    chains = await load_chains()
    keys = {tuple(g["series_key"].split(":")) for g in open_gaps}
    segs = {seg.key for k in keys if k in chains for seg in chains[k]}
    n = await collect_travel_time(ctx, full=True, only=segs)
    await recompute_corridors({k: v for k, v in chains.items() if k in keys}, start, now_kst())
    await db.execute("""UPDATE ops.slot_gap SET attempts = attempts + 1
                        WHERE job_name = %s AND backfilled_at IS NULL AND slot_ts >= %s""", (JOB, start))
    return n


# ---------------------------------------------------------------- 전국 교통량 · 문자 안내 · 톨게이트

async def collect_volume(ctx: JobContext) -> int:
    rows = ex.parse_traffic_all(await ex.traffic_all(ctx))
    await db.executemany("""
        INSERT INTO ts.road_volume (slot_ts, ex_div_code, tcs_type, car_type, volume) VALUES (%s, %s, %s, %s, %s)
        ON CONFLICT (slot_ts, ex_div_code, tcs_type, car_type) DO UPDATE SET volume = EXCLUDED.volume, collected_at = now()
        """, [(r["slot_ts"], r["ex_div_code"], r["tcs_type"], r["car_type"], r["volume"]) for r in rows])
    ctx.rows += len(rows)
    return len(rows)


PROMO_TYPE = "15"  # 이벤트/홍보
DIRECTION_RE = re.compile(r"[0-9A-Za-z가-힣]+\s*방향")


def match_incident(inc: dict, corridor_routes: dict[str, set[str]], corridor_places: dict[str, set[str]],
                   corridor_main_route: dict[str, str]) -> list[str]:
    """돌발 문자 → 길 매칭 규칙 M-v1 (FR-405).
    홍보성(유형 15)은 매칭하지 않는다. 노선명이 길 구간의 노선과 같고,
    본문에 길 영업소명(방향 표기 'OO방향' 제외)이 나오거나 그 노선이 길 주 노선이면 매칭."""
    if inc.get("type_code") == PROMO_TYPE or not inc.get("route_name"):
        return []
    # 'OO방향' 은 위치가 아니라 진행 방향이므로 지운 뒤 영업소명을 찾는다
    text = DIRECTION_RE.sub("", inc["content"]).replace(" ", "")
    route = inc["route_name"].replace(" ", "")
    out = []
    for cid, routes in corridor_routes.items():
        if route not in routes:
            continue
        mentioned = any(p in text for p in corridor_places.get(cid, ()) if len(p) >= 2)
        if mentioned or corridor_main_route.get(cid) == route:
            out.append(cid)
    return sorted(out)


async def corridor_route_index() -> tuple[dict[str, set[str]], dict[str, set[str]], dict[str, str]]:
    rows = await db.fetch("""
        SELECT r.corridor_id, u.unit_name, COALESCE(u.route_name, '') AS route_name, r.distance_km
        FROM ref.corridor_road r JOIN ref.toll_unit u ON u.unit_code IN (r.start_unit_code, r.end_unit_code)""")
    routes: dict[str, set[str]] = defaultdict(set)
    places: dict[str, set[str]] = defaultdict(set)
    weight: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    for r in rows:
        rn = r["route_name"].replace(" ", "")
        if rn:
            routes[r["corridor_id"]].add(rn)
            weight[r["corridor_id"]][rn] += float(r["distance_km"])
        places[r["corridor_id"]].add(r["unit_name"])
    main = {cid: max(w, key=w.get) for cid, w in weight.items() if w}
    return dict(routes), dict(places), main


async def collect_incidents(ctx: JobContext) -> int:
    items, page = [], 1
    while True:
        body = await ex.sms_page(ctx, page)
        items.extend(ex.parse_sms(body))
        if page >= int(body.get("pageSize") or 0):
            break
        page += 1
    routes, places, main = await corridor_route_index()
    rows = [(i["msg_hash"], i["sent_at"], i["type_code"], i["type_name"], i["route_no"], i["route_name"],
             i["direction_txt"], i["process_name"], i["content"], match_incident(i, routes, places, main),
             i["lat"], i["lon"], i["point_name"]) for i in items]
    # 응답 목록 = 지금 안내 중인 문자. last_seen_at 이 최근이면 '안내 중'으로 본다.
    await db.executemany("""
        INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, route_no, route_name, direction_txt,
                                      process_name, content, corridor_ids, lat, lon, point_name)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (msg_hash) DO UPDATE SET last_seen_at = now(), process_name = EXCLUDED.process_name,
          corridor_ids = EXCLUDED.corridor_ids, lat = EXCLUDED.lat, lon = EXCLUDED.lon, point_name = EXCLUDED.point_name""", rows)
    ctx.rows += len(rows)
    return len(rows)


async def sync_toll_units(ctx: JobContext) -> int:
    units, page = [], 1
    while True:
        body = await ex.units_page(ctx, page)
        units.extend(ex.parse_units(body))
        if page >= int(body.get("pageSize") or 0):
            break
        page += 1
    dedup = {u["unit_code"]: u for u in units}
    await db.executemany("""
        INSERT INTO ref.toll_unit (unit_code, unit_name, route_no, route_name, lat, lon, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, now())
        ON CONFLICT (unit_code) DO UPDATE SET unit_name = EXCLUDED.unit_name, route_no = EXCLUDED.route_no,
          route_name = EXCLUDED.route_name, lat = COALESCE(EXCLUDED.lat, ref.toll_unit.lat),
          lon = COALESCE(EXCLUDED.lon, ref.toll_unit.lon), updated_at = now()""",
        [(u["unit_code"], u["unit_name"], u["route_no"], u["route_name"], u["lat"], u["lon"]) for u in dedup.values()])
    nulls = sum(1 for u in dedup.values() if u["lat"] is None)
    ctx.note(f"톨게이트 {len(dedup)}개 (좌표 없음 {nulls}개, {nulls / max(len(dedup), 1):.1%})")
    ctx.rows += len(dedup)
    return len(dedup)



async def reclassify_all(since: dt.datetime | None = None) -> tuple[int, int]:
    """품질 규칙을 바꾼 뒤 저장된 원본 행의 quality 를 다시 매기고 길 합산을 다시 계산한다 (API 호출 없음)."""
    s = settings()
    chains = await load_chains()
    segs = unique_segments(chains)
    since = since or (now_kst() - dt.timedelta(days=60))
    rows = await db.fetch("""SELECT slot_ts, start_unit_code, end_unit_code, car_type, travel_sec, min_sec, vehicles, quality
                             FROM ts.road_travel_time WHERE slot_ts >= %s""", (since,))
    changed = []
    for r in rows:
        seg = segs.get((r["start_unit_code"], r["end_unit_code"]))
        if not seg:
            continue
        q = classify(seg.distance_km, r["travel_sec"], r["vehicles"], r["min_sec"], hard_min_speed=s.q_hard_min_speed_kmh,
                     max_speed=s.q_max_speed_kmh, free_min_speed=s.q_free_min_speed_kmh, mix_ratio=s.q_mix_ratio)
        if q != r["quality"]:
            changed.append((q, r["slot_ts"], r["start_unit_code"], r["end_unit_code"], r["car_type"]))
    await db.executemany("""UPDATE ts.road_travel_time SET quality = %s
                            WHERE slot_ts = %s AND start_unit_code = %s AND end_unit_code = %s AND car_type = %s""", changed)
    if not rows:
        return 0, 0
    tmin = min(r["slot_ts"] for r in rows).astimezone(KST)
    tmax = max(r["slot_ts"] for r in rows).astimezone(KST)
    n = await recompute_corridors(chains, tmin, tmax)
    return len(changed), n
