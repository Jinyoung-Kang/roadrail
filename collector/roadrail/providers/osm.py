"""OpenStreetMap 철도 선로 (Overpass API). © OpenStreetMap contributors — ODbL.

전국 한 번에 요청하면 공개 서버가 504 로 끊기는 일이 잦아(2026-09-25 실측) 국토를 4구역으로 나눠 받고,
구역마다 미러 서버를 바꿔 가며 재시도한다. 결과는 way 단위(노드 ID + 좌표).
공개 미러가 몹시 느린 날이 있어(작은 조회도 60초) 받은 구역은 Redis 에 3일 캐시한다 — 다시 실행하면 못 받은 구역만 받는다.
"""
from __future__ import annotations

import asyncio
import base64
import json
import zlib
from pathlib import Path

from ..core import rds
from .base import JobContext, ProviderError

MIRRORS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
           "https://overpass.private.coffee/api/interpreter"]
TILES = [(33.0, 124.5, 35.6, 127.6), (33.0, 127.6, 35.6, 130.0), (35.6, 124.5, 38.7, 127.6), (35.6, 127.6, 38.7, 130.0)]


def query(tile: tuple[float, float, float, float]) -> str:
    s, w, n, e = tile
    return f'[out:json][timeout:180];way["railway"="rail"]({s},{w},{n},{e});out skel geom qt;'


CACHE_TTL_S = 3 * 86400


def tile_key(i: int) -> str:
    return f"rr:osm:tile:{i}"


def compact(elements: list[dict]) -> list[dict]:
    """Overpass 응답 → 그래프에 필요한 way 만 (id · 노드 · 좌표)."""
    return [{"id": w["id"], "nodes": w["nodes"], "geometry": [{"lat": g["lat"], "lon": g["lon"]} for g in w["geometry"]]}
            for w in elements if w.get("type") == "way" and w.get("nodes") and w.get("geometry")]


def pack(ways: list[dict]) -> str:
    return base64.b64encode(zlib.compress(json.dumps(ways, separators=(",", ":")).encode(), 6)).decode()


def unpack(s: str) -> list[dict]:
    return json.loads(zlib.decompress(base64.b64decode(s)))


def ways_from_file(path: str | Path) -> list[dict]:
    """미리 받아 둔 Overpass JSON(`out geom`) 파일 — 공개 미러에 닿지 않는 환경용 (roadrail rail-geometry FILE)."""
    return compact(json.loads(Path(path).read_text()).get("elements") or [])


async def rail_ways(ctx: JobContext, attempts: int = 6) -> list[dict]:
    r = rds.client()
    ways: dict[int, dict] = {}
    for t, tile in enumerate(TILES):
        cached = await r.get(tile_key(t))
        if cached:
            got = unpack(cached)
            ctx.note(f"구역 {t + 1}: 캐시 {len(got)}개")
        else:
            for i in range(attempts):
                try:
                    body = await ctx.get_json("OSM", "overpass", MIRRORS[i % len(MIRRORS)], {"data": query(tile)},
                                              headers={"User-Agent": "roadrail/0.1 (portfolio)"}, retries=0)
                    got = compact(body.get("elements") or [])
                    await r.set(tile_key(t), pack(got), ex=CACHE_TTL_S)
                    break
                except ProviderError:
                    if i == attempts - 1:
                        raise
                    await asyncio.sleep(5)
        for w in got:
            ways[w["id"]] = w
    return list(ways.values())
