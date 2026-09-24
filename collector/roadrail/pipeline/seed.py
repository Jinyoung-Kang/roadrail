"""코리도 seed 적용 (FR-102). 멱등: 같은 seed 를 다시 적용하면 변경 0건."""
from __future__ import annotations

import logging
from pathlib import Path

import yaml

from ..core import db
from ..core.config import settings
from ..core.log import log
from ..providers.kma import latlon_to_grid

logger = logging.getLogger(__name__)

# ON CONFLICT … DO UPDATE … WHERE (값이 다를 때만) → rowcount 가 실제 변경 수
SQL_UNIT = """
INSERT INTO ref.toll_unit (unit_code, unit_name, route_no, lat, lon) VALUES (%s, %s, %s, %s, %s)
ON CONFLICT (unit_code) DO UPDATE SET unit_name = EXCLUDED.unit_name,
  route_no = COALESCE(ref.toll_unit.route_no, EXCLUDED.route_no),
  lat = COALESCE(ref.toll_unit.lat, EXCLUDED.lat), lon = COALESCE(ref.toll_unit.lon, EXCLUDED.lon)
WHERE ref.toll_unit.unit_name IS DISTINCT FROM EXCLUDED.unit_name
   OR (ref.toll_unit.lat IS NULL AND EXCLUDED.lat IS NOT NULL)
"""
SQL_STATION = """
INSERT INTO ref.station (stn_cd, stn_nm, lat, lon, source) VALUES (%s, %s, %s, %s, %s)
ON CONFLICT (stn_cd) DO UPDATE SET stn_nm = EXCLUDED.stn_nm, lat = EXCLUDED.lat, lon = EXCLUDED.lon, source = EXCLUDED.source
WHERE (ref.station.lat, ref.station.lon, ref.station.source) IS DISTINCT FROM (EXCLUDED.lat, EXCLUDED.lon, EXCLUDED.source)
"""
SQL_CORRIDOR = """
INSERT INTO ref.corridor (corridor_id, name, origin_city, dest_city, sort_order) VALUES (%s, %s, %s, %s, %s)
ON CONFLICT (corridor_id) DO UPDATE SET name = EXCLUDED.name, origin_city = EXCLUDED.origin_city,
  dest_city = EXCLUDED.dest_city, sort_order = EXCLUDED.sort_order
WHERE (ref.corridor.name, ref.corridor.origin_city, ref.corridor.dest_city, ref.corridor.sort_order)
   IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.origin_city, EXCLUDED.dest_city, EXCLUDED.sort_order)
"""
SQL_ROAD = """
INSERT INTO ref.corridor_road (corridor_id, direction, seq, start_unit_code, end_unit_code, distance_km)
VALUES (%s, %s, %s, %s, %s, %s)
ON CONFLICT (corridor_id, direction, seq) DO UPDATE SET start_unit_code = EXCLUDED.start_unit_code,
  end_unit_code = EXCLUDED.end_unit_code, distance_km = EXCLUDED.distance_km
WHERE (ref.corridor_road.start_unit_code, ref.corridor_road.end_unit_code, ref.corridor_road.distance_km)
   IS DISTINCT FROM (EXCLUDED.start_unit_code, EXCLUDED.end_unit_code, EXCLUDED.distance_km)
"""
SQL_RAIL = """
INSERT INTO ref.corridor_rail (corridor_id, direction, dep_stn_cd, arr_stn_cd) VALUES (%s, %s, %s, %s)
ON CONFLICT (corridor_id, direction) DO UPDATE SET dep_stn_cd = EXCLUDED.dep_stn_cd, arr_stn_cd = EXCLUDED.arr_stn_cd
WHERE (ref.corridor_rail.dep_stn_cd, ref.corridor_rail.arr_stn_cd) IS DISTINCT FROM (EXCLUDED.dep_stn_cd, EXCLUDED.arr_stn_cd)
"""
SQL_ENV = """
INSERT INTO ref.corridor_env_point (corridor_id, role, name, lat, lon, nx, ny, sido_name)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (corridor_id, role) DO UPDATE SET name = EXCLUDED.name, lat = EXCLUDED.lat, lon = EXCLUDED.lon,
  nx = EXCLUDED.nx, ny = EXCLUDED.ny, sido_name = EXCLUDED.sido_name
WHERE (ref.corridor_env_point.name, ref.corridor_env_point.lat, ref.corridor_env_point.lon, ref.corridor_env_point.sido_name)
   IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.lat, EXCLUDED.lon, EXCLUDED.sido_name)
"""


def load_seed(path: Path | None = None) -> dict:
    return yaml.safe_load((path or settings().seed_path).read_text())


async def apply_seed(path: Path | None = None) -> int:
    doc = load_seed(path)
    changes = 0
    p = await db.pool()
    async with p.connection() as conn, conn.transaction(), conn.cursor() as cur:
        async def run(sql, params):
            nonlocal changes
            await cur.execute(sql, params)
            changes += cur.rowcount

        for u in doc["units"]:
            await run(SQL_UNIT, (u["code"], u["name"], u.get("routeNo"), u.get("lat"), u.get("lon")))
        for s in doc["stations"]:
            await run(SQL_STATION, (s["code"], s["name"], s.get("lat"), s.get("lon"), s.get("source")))
        for order, c in enumerate(doc["corridors"]):
            cid = c["id"]
            await run(SQL_CORRIDOR, (cid, c["name"], c["originCity"], c["destCity"], order))
            for direction in ("DN", "UP"):
                chain = c["road"][direction]
                for seq, seg in enumerate(chain, start=1):
                    await run(SQL_ROAD, (cid, direction, seq, seg["start"], seg["end"], seg["distanceKm"]))
                await run("DELETE FROM ref.corridor_road WHERE corridor_id = %s AND direction = %s AND seq > %s",
                          (cid, direction, len(chain)))
                r = c["rail"][direction]
                await run(SQL_RAIL, (cid, direction, r["dep"], r["arr"]))
            for e in c["env"]:
                nx, ny = latlon_to_grid(e["lat"], e["lon"])
                await run(SQL_ENV, (cid, e["role"], e["name"], e["lat"], e["lon"], nx, ny, e["sido"]))
        ids = [c["id"] for c in doc["corridors"]]
        await run("UPDATE ref.corridor SET active = (corridor_id = ANY(%s)) "
                  "WHERE active IS DISTINCT FROM (corridor_id = ANY(%s))", (ids, ids))
    log(logger, "seed 적용", changes=changes, corridors=len(doc["corridors"]))
    return changes
