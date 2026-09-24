"""환경 수집: 단기예보 · 대기질 · 카카오 미래 운행 정보 (교차검증)."""
from __future__ import annotations

import datetime as dt
import logging

from ..core import db
from ..core.timeutil import now_kst
from ..providers import airkorea, kakao, kma
from ..providers.base import JobContext, ProviderError

logger = logging.getLogger(__name__)


async def collect_weather(ctx: JobContext) -> int:
    base = kma.latest_base(now_kst())
    grids = await db.fetch("SELECT DISTINCT nx, ny FROM ref.corridor_env_point ORDER BY 1, 2")
    have = {(r["nx"], r["ny"]) for r in await db.fetch(
        "SELECT DISTINCT nx, ny FROM env.weather_fcst WHERE base_at = %s", (base,))}
    n = 0
    for g in grids:
        if (g["nx"], g["ny"]) in have:
            continue  # 같은 발표는 다시 받지 않음 (예산 절약 · 멱등)
        try:
            rows = kma.parse_vilage(await kma.vilage(ctx, base, g["nx"], g["ny"]))
        except ProviderError as e:
            ctx.notes.append(f"PARTIAL: 격자 {g['nx']},{g['ny']} {e}")
            continue
        await db.executemany("""
            INSERT INTO env.weather_fcst (base_at, fcst_at, nx, ny, category, value) VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (base_at, fcst_at, nx, ny, category) DO UPDATE SET value = EXCLUDED.value""",
            [(r["base_at"], r["fcst_at"], r["nx"], r["ny"], r["category"], r["value"]) for r in rows])
        n += len(rows)
    ctx.rows += n
    return n


async def collect_air(ctx: JobContext) -> int:
    sidos = [r["sido_name"] for r in await db.fetch(
        "SELECT DISTINCT sido_name FROM ref.corridor_env_point ORDER BY 1")]
    n = 0
    for s in sidos:
        try:
            rows = airkorea.parse_sido(await airkorea.sido(ctx, s))
        except ProviderError as e:
            ctx.notes.append(f"PARTIAL: {s} {e}")
            continue
        await db.executemany("""
            INSERT INTO env.air_quality (data_time, station_name, sido_name, pm10, pm25, khai_value, khai_grade, pm25_grade)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (data_time, station_name) DO UPDATE SET pm10 = EXCLUDED.pm10, pm25 = EXCLUDED.pm25,
              khai_value = EXCLUDED.khai_value, khai_grade = EXCLUDED.khai_grade, pm25_grade = EXCLUDED.pm25_grade""",
            [(r["data_time"], r["station_name"], r["sido_name"], r["pm10"], r["pm25"], r["khai_value"],
              r["khai_grade"], r["pm25_grade"]) for r in rows])
        n += len(rows)
    ctx.rows += n
    return n


async def corridor_endpoints() -> list[dict]:
    """길·방향별 도로 체인의 첫 영업소 → 마지막 영업소 좌표."""
    return await db.fetch("""
        WITH ends AS (
          SELECT corridor_id, direction,
                 (array_agg(start_unit_code ORDER BY seq))[1] AS s,
                 (array_agg(end_unit_code ORDER BY seq DESC))[1] AS e
          FROM ref.corridor_road GROUP BY 1, 2)
        SELECT ends.corridor_id, ends.direction, us.lat AS slat, us.lon AS slon, ue.lat AS elat, ue.lon AS elon
        FROM ends JOIN ref.toll_unit us ON us.unit_code = ends.s JOIN ref.toll_unit ue ON ue.unit_code = ends.e
        JOIN ref.corridor c ON c.corridor_id = ends.corridor_id AND c.active
        ORDER BY 1, 2""")


async def collect_kakao_eta(ctx: JobContext) -> int:
    now = now_kst()
    depart = (now + dt.timedelta(minutes=10)).replace(second=0, microsecond=0)
    depart = depart.replace(minute=depart.minute - depart.minute % 10)
    rows = []
    for c in await corridor_endpoints():
        try:
            res = kakao.parse_future(await kakao.future_directions(ctx, (c["slat"], c["slon"]), (c["elat"], c["elon"]), depart))
        except ProviderError as e:
            ctx.notes.append(f"PARTIAL: {c['corridor_id']}:{c['direction']} {e}")
            continue
        if res:
            rows.append((now, depart, c["corridor_id"], c["direction"], res["duration_sec"], res["distance_m"]))
    await db.executemany("""INSERT INTO ana.kakao_eta (requested_at, depart_at, corridor_id, direction, duration_sec, distance_m)
                            VALUES (%s, %s, %s, %s, %s, %s) ON CONFLICT DO NOTHING""", rows)
    ctx.rows += len(rows)
    return len(rows)
