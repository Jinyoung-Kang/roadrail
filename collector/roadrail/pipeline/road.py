"""도로 수집 파이프라인 (FR-201~203, FR-405).

영업소 간 통행시간 API 는 하루치를 차종 → 시각 순으로 정렬해 99행씩 준다(자정 뒤 약 01:30 까지는 전날을 계속 준다).
그래서 구간마다 '그 데이터 날짜에 지금까지 본 1종 행 수'(Redis ex:tail:*) 를 기억해 **꼬리 페이지만** 다시 받는다.
(보통 구간당 1회 호출. 기획서 가정인 '5분 슬롯 × 호출 1회' 대신 '10분마다 꼬리 1~2페이지'.)
받은 슬롯 범위만 길 합산을 다시 계산하므로 늦게 공개된 값도 자연스럽게 반영된다(멱등 UPSERT).
"""
from __future__ import annotations

import asyncio
import datetime as dt
import logging
import re
from collections import Counter, defaultdict
from dataclasses import dataclass

from ..analytics.road_quality import Segment, aggregate_corridor, classify, despike
from ..core import db, rds
from ..core.config import settings
from ..core.timeutil import KST, day_start, now_kst, slots_between
from ..providers import ex, utic
from ..providers.base import JobContext, ProviderError

logger = logging.getLogger(__name__)

JOB = "road_travel_time"
# 공개 지연(약 3시간)에 여유를 둔 시간 — 이보다 오래된 슬롯이 전체를 다시 받아도 비면 원천에 표본이 없는 것(NO_SAMPLES)
PUBLISH_MARGIN = dt.timedelta(hours=4)
# 다시 받지 않는 결측 사유 — 원천이 그날을 더는 주지 않음 · 원천에 표본이 없음
CLOSED_REASONS = ("SOURCE_EXPIRED", "NO_SAMPLES")

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


@dataclass(frozen=True)
class TailCursor:
    """구간의 꼬리 위치 — 원천 데이터의 날짜(stdDate)와 그날 본 1종 행 수.
    날짜는 벽시계가 아니라 받은 데이터에서 읽는다: 원천은 자정 뒤 한동안 전날을 주기 때문에, 벽시계 날짜로 기억하면
    전날의 행 수가 새 날의 커서가 되어 원천이 새 날로 넘어간 뒤 빈 꼬리 쪽만 요청했다(H1)."""
    day: str
    seen: int

    @property
    def page(self) -> int:
        return self.seen // ex.PAGE + 1

    def dump(self) -> str:
        return f"{self.day}:{self.seen}"


def parse_cursor(raw: str | None) -> TailCursor | None:
    """저장 값 'YYYYMMDD:행수' → 커서. 형식이 다르면 없는 것으로 본다 — 처음부터 받는다."""
    day, sep, seen = (raw or "").partition(":")
    if sep and len(day) == 8 and day.isdigit() and seen.isdigit():
        return TailCursor(day, int(seen))
    return None


def tail_is_stale(cur: TailCursor, page: int, page_day: str | None, page_size: int) -> bool:
    """꼬리 쪽(2쪽 이상)을 요청했는데 그 쪽이 커서와 다른 날의 것이거나 비었으면 — 원천이 날짜를 넘겨 목록이 줄었다."""
    return page > 1 and (page_day != cur.day or page > page_size)


async def fetch_segment(ctx: JobContext, seg: Segment, full: bool) -> tuple[list[tuple], str, TailCursor | None]:
    """꼬리(또는 전체) 페이지를 받아 (저장할 행, 꼬리 키, 새 꼬리 커서)를 돌려준다.
    커서는 여기서 기록하지 않는다 — 행을 저장한 뒤에만 옮겨야 실패한 수집의 슬롯을 다음 수집이 건너뛰지 않는다."""
    s = settings()
    key = rds.tail_key(seg.start, seg.end)
    cur = None if full else parse_cursor(await rds.client().get(key))
    page = cur.page if cur else 1
    day, seen = (cur.day, cur.seen) if cur else (None, 0)
    out: list[tuple] = []
    while True:
        body = await ex.travel_page(ctx, seg.start, seg.end, page)
        rows, n_type, page_size, more = ex.parse_travel_page(body)
        page_day = ex.page_day(body)
        if cur is not None and tail_is_stale(cur, page, page_day, page_size):
            cur, page, day, seen = None, 1, None, 0   # 새 날을 1쪽부터
            continue
        cur = None  # 꼬리 판정은 첫 요청에서만
        if page_day and page_day != day:
            day, seen = page_day, (page - 1) * ex.PAGE
        for t in rows:
            q = classify(seg.distance_km, t.avg_sec, t.vehicles, t.min_sec, hard_min_speed=s.q_hard_min_speed_kmh,
                         max_speed=s.q_max_speed_kmh, free_min_speed=s.q_free_min_speed_kmh, mix_ratio=s.q_mix_ratio)
            out.append((t.slot_ts, t.start, t.end, t.car_type, t.avg_sec, t.min_sec, t.max_sec, t.vehicles, q))
        if n_type:
            seen = (page - 1) * ex.PAGE + n_type
        if not more:
            break
        page += 1
    return out, key, TailCursor(day, seen) if day else None


async def collect_travel_time(ctx: JobContext, full: bool = False, only: set[tuple[str, str]] | None = None) -> int:
    chains = await load_chains()
    segs = unique_segments(chains)
    if only is not None:
        segs = {k: v for k, v in segs.items() if k in only}
    results = await asyncio.gather(*(fetch_segment(ctx, seg, full) for seg in segs.values()), return_exceptions=True)
    rows: list[tuple] = []
    tails: dict[str, TailCursor] = {}
    failed, unexpected = 0, None
    for seg, res in zip(segs.values(), results, strict=True):
        if isinstance(res, BaseException):
            failed += 1
            ctx.note(f"구간 {seg.start}->{seg.end} 실패: {res}")
            if not isinstance(res, ProviderError):
                unexpected = unexpected or res  # 받은 구간을 저장한 뒤에 다시 던진다
            continue
        seg_rows, key, cursor = res
        rows.extend(seg_rows)
        if cursor is not None:
            tails[key] = cursor
    await db.executemany(SQL_UPSERT_TT, rows)
    ctx.rows += len(rows)
    # 저장이 끝난 뒤에만 꼬리 위치를 옮긴다 (BUG-05)
    if tails:
        async with rds.client().pipeline(transaction=False) as pipe:
            for key, cursor in tails.items():
                pipe.set(key, cursor.dump(), ex=172800)
            await pipe.execute()
    if failed:
        ctx.notes.append(f"PARTIAL: 구간 {failed}/{len(segs)} 실패")
    if rows:
        tmin = min(r[0] for r in rows)
        tmax = max(r[0] for r in rows)
        touched = {(r[1], r[2]) for r in rows}
        await recompute_corridors(chains, tmin, tmax, touched)
    for day in sorted({r[0].astimezone(KST).date() for r in rows} | {now_kst().date()}):
        await sweep_gaps(day)  # 원천은 자정 뒤에도 한동안 전날을 준다 — 받은 데이터의 날짜마다
    if unexpected is not None:
        raise unexpected
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
    await db.executemany("""UPDATE ops.slot_gap SET backfilled_at = now()
                            WHERE job_name = %s AND series_key = %s AND slot_ts = %s AND backfilled_at IS NULL""",
                         [(JOB, key, ts) for key, ts in resolved])
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


async def source_day(ctx: JobContext, chains: dict[tuple[str, str], list[Segment]]) -> str | None:
    """원천이 지금 주는 데이터 날짜(YYYYMMDD) — 구간 하나의 1쪽으로 확인(호출 1건). 빈 응답이면 None(모름)."""
    seg = next(iter(unique_segments(chains).values()), None)
    if seg is None:
        return None
    return ex.page_day(await ex.travel_page(ctx, seg.start, seg.end, 1))


async def backfill_gaps(ctx: JobContext) -> int:
    """열린 결측 → 해당 길 구간을 처음부터 다시 받아 재합산.
    원천은 자정 뒤 약 01:30 까지 전날을 주므로 어제 결측은 원천이 아직 어제를 주는 동안 다시 받고,
    원천이 넘어간 것을 확인한 뒤에만 SOURCE_EXPIRED 로 닫는다(M2). 그보다 오래된 결측은 바로 닫는다.
    공개 지연이 지난 슬롯이 전체를 다시 받아도 비면 NO_SAMPLES 로 두고 더 받지 않는다 — 새벽처럼 원천 표본이 원래 적은
    슬롯을 2시간마다 6번씩 다시 받던 것(하루 약 900건, M3). 완전성 계산에서도 뺀다(API)."""
    today = now_kst().date()
    start = day_start(today)
    y_start = start - dt.timedelta(days=1)
    expire = """UPDATE ops.slot_gap SET reason = 'SOURCE_EXPIRED'
                WHERE job_name = %s AND backfilled_at IS NULL AND slot_ts < %s AND reason <> ALL(%s)"""
    await db.execute(expire, (JOB, y_start, list(CLOSED_REASONS)))
    chains = await load_chains()
    since = start
    yesterday_open = await db.fetchone("""SELECT 1 AS x FROM ops.slot_gap
                                          WHERE job_name = %s AND backfilled_at IS NULL AND reason <> ALL(%s)
                                            AND slot_ts >= %s AND slot_ts < %s AND attempts < 6 LIMIT 1""",
                                       (JOB, list(CLOSED_REASONS), y_start, start))
    if yesterday_open:
        served = await source_day(ctx, chains)
        if served == y_start.strftime("%Y%m%d"):
            since = y_start                        # 원천이 아직 어제를 준다 — 어제 결측도 다시 받는다
        elif served is not None:
            await db.execute(expire, (JOB, start, list(CLOSED_REASONS)))  # 원천이 넘어갔다 — 어제 결측은 이제 받을 수 없다
    open_gaps = await db.fetch("""SELECT DISTINCT series_key FROM ops.slot_gap
                                  WHERE job_name = %s AND backfilled_at IS NULL AND reason <> ALL(%s)
                                    AND slot_ts >= %s AND attempts < 6""", (JOB, list(CLOSED_REASONS), since))
    if not open_gaps:
        return 0
    keys = {tuple(g["series_key"].split(":")) for g in open_gaps}
    segs = {seg.key for k in keys if k in chains for seg in chains[k]}
    n = await collect_travel_time(ctx, full=True, only=segs)
    now = now_kst()
    await recompute_corridors({k: v for k, v in chains.items() if k in keys}, since, now)
    if not any(note.startswith("PARTIAL") for note in ctx.notes):   # 모든 구간을 다시 받았을 때만 '원천에 없음'으로 판단
        await db.execute("""UPDATE ops.slot_gap SET reason = 'NO_SAMPLES'
                            WHERE job_name = %s AND backfilled_at IS NULL AND reason IN ('LOW_COVERAGE', 'NO_DATA')
                              AND series_key = ANY(%s) AND slot_ts >= %s AND slot_ts < %s""",
                         (JOB, [g["series_key"] for g in open_gaps], since, now - PUBLISH_MARGIN))
    await db.execute("""UPDATE ops.slot_gap SET attempts = attempts + 1
                        WHERE job_name = %s AND backfilled_at IS NULL AND reason <> ALL(%s) AND slot_ts >= %s""",
                     (JOB, list(CLOSED_REASONS), since))
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


async def collect_utic_incidents(ctx: JobContext) -> int:
    """경찰청 UTIC 돌발정보(일반 도로 포함) → ts.road_incident(source='UTIC').
    응답 목록 = 지금 진행 중 — 도로공사 문자와 같이 last_seen_at 이 최근이면 '안내 중'으로 본다. 키가 없으면 부르지 않는다."""
    if not settings().utic_api_key:
        ctx.note("UTIC 키 없음 — 건너뜀 (.env UTIC_API_KEY)")
        return 0
    items = utic.parse_incidents(await utic.incidents(ctx))
    await db.executemany("""
        INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, route_name, content, lat, lon, point_name,
                                      source, end_at, lane)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'UTIC', %s, %s)
        ON CONFLICT (msg_hash) DO UPDATE SET last_seen_at = now(), type_name = EXCLUDED.type_name,
          route_name = EXCLUDED.route_name, content = EXCLUDED.content, lat = EXCLUDED.lat, lon = EXCLUDED.lon,
          point_name = EXCLUDED.point_name, end_at = EXCLUDED.end_at, lane = EXCLUDED.lane""",
        [(i["msg_hash"], i["sent_at"], i["type_code"], i["type_name"], i["route_name"], i["content"], i["lat"], i["lon"],
          i["point_name"], i["end_at"], i["lane"]) for i in items])
    by = Counter(i["type_name"] for i in items)
    ctx.note(f"UTIC 돌발 {len(items)}건" + (" (" + " · ".join(f"{k} {v}" for k, v in by.most_common()) + ")" if items else ""))
    ctx.rows += len(items)
    return len(items)


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
