"""실제 선로 경로 (rail_geometry 작업) — OSM 선로를 받아 역 쌍별 경로를 ref.rail_link 에 저장."""
from __future__ import annotations

import datetime as dt
import json
import logging
from collections import defaultdict

from ..analytics.rail_graph import RailGraph, hav, simplify
from ..core import db
from ..core.timeutil import now_kst
from ..providers import osm
from ..providers.base import JobContext

logger = logging.getLogger(__name__)
REFRESH_DAYS = 28
MAX_RATIO = 3.6  # 선로 길이 / 직선 — 이보다 크면 잘못 붙은 것으로 보고 버린다 (실측 최대 3.44: 태백–도계 루프 터널)


async def build_rail_links(ctx: JobContext, source_file: str | None = None) -> int:
    have = await db.fetchone("SELECT count(*) AS n, max(computed_at) AS at FROM ref.rail_link")
    if not source_file and ctx.trigger == "SCHEDULE" and have["n"] and have["at"] > now_kst() - dt.timedelta(days=REFRESH_DAYS):
        ctx.note(f"선로 경로 {have['n']}쌍 최신 ({have['at']:%m-%d} 계산) — 건너뜀")
        return 0
    try:
        ways = osm.ways_from_file(source_file) if source_file else await osm.rail_ways(ctx)
    except Exception:
        if have["n"]:
            ctx.note(f"기존 선로 경로 {have['n']}쌍은 그대로 둡니다 — 내일 05:00 에 다시 시도")
        raise
    g = RailGraph(ways)
    stations = await db.fetch("SELECT stn_cd, lat, lon FROM ref.station WHERE lat IS NOT NULL")
    unsnapped = [s["stn_cd"] for s in stations if not g.add_station(s["stn_cd"], s["lat"], s["lon"])]
    pairs = await db.fetch("""
        SELECT DISTINCT a.stn_cd AS a, b.stn_cd AS b FROM rail.run_info a
        JOIN rail.run_info b ON b.run_ymd = a.run_ymd AND b.trn_no = a.trn_no AND b.run_seq = a.run_seq + 1
        WHERE a.run_ymd > (SELECT max(run_ymd) FROM rail.run_info) - 14""")
    by_src: dict[str, set[str]] = defaultdict(set)
    for p in pairs:
        by_src[p["a"]].add(p["b"])
    rows, rejected = [], 0
    for a, targets in by_src.items():
        for b, (length, path) in g.routes_from(a, targets).items():
            straight = hav(g.stations[a], g.stations[b])
            if straight > 0.3 and length / straight > MAX_RATIO:
                rejected += 1
                continue
            pts = simplify(path)
            rows.append((a, b, json.dumps([[round(la, 5), round(lo, 5)] for la, lo in pts]), round(length, 2), round(straight, 2)))
    await db.executemany("""
        INSERT INTO ref.rail_link (dep_stn_cd, arr_stn_cd, path, length_km, straight_km, source, computed_at)
        VALUES (%s, %s, %s::jsonb, %s, %s, 'OSM', now())
        ON CONFLICT (dep_stn_cd, arr_stn_cd) DO UPDATE SET path = EXCLUDED.path, length_km = EXCLUDED.length_km,
          straight_km = EXCLUDED.straight_km, computed_at = now()""", rows)
    ctx.rows += len(rows)
    ctx.note(f"선로 {len(ways)}개 · 역 쌍 {len(pairs)}개 중 {len(rows)}개 경로 저장 (버림 {rejected}, 선로에 못 붙은 역 {len(unsnapped)})")
    return len(rows)
