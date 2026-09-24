"""카카오 로컬 키워드 검색 (역 좌표 보정, FR-103) · 카카오모빌리티 미래 운행 정보 길찾기 (FR-504)."""
from __future__ import annotations

import datetime as dt

from ..core.config import settings
from .base import JobContext

KEYWORD = "https://dapi.kakao.com/v2/local/search/keyword.json"
FUTURE = "https://apis-navi.kakaomobility.com/v1/future/directions"


def _h() -> dict:
    return {"Authorization": f"KakaoAK {settings().kakao_rest_api_key}"}


def parse_future(body: dict) -> dict | None:
    routes = body.get("routes") or []
    if not routes or routes[0].get("result_code") != 0:
        return None
    s = routes[0]["summary"]
    return dict(duration_sec=int(s["duration"]), distance_m=int(s["distance"]))


async def future_directions(ctx: JobContext, origin: tuple[float, float], dest: tuple[float, float],
                            depart_at: dt.datetime) -> dict:
    # origin/dest = (lat, lon) → API 는 "경도,위도"
    return await ctx.get_json("KAKAO", "future/directions", FUTURE, dict(
        origin=f"{origin[1]},{origin[0]}", destination=f"{dest[1]},{dest[0]}",
        departure_time=depart_at.strftime("%Y%m%d%H%M"), summary="true"), headers=_h())


def parse_station(body: dict) -> tuple[float, float] | None:
    docs = body.get("documents") or []
    rail = [d for d in docs if "기차역" in (d.get("category_name") or "")] or docs
    return (float(rail[0]["y"]), float(rail[0]["x"])) if rail else None


async def keyword(ctx: JobContext, query: str) -> dict:
    return await ctx.get_json("KAKAO", "local/keyword", KEYWORD, dict(query=query, size=5), headers=_h())
