"""한국천문연구원 특일 정보 (data.go.kr SpcdeInfoService) — 공휴일 · 대체공휴일 · 선거일 등 쉬는 날.

getRestDeInfo(solYear) 한 번에 그해 휴일 전체 (2026년 22일 실측). isHoliday=Y 만 쓴다.
"""
from __future__ import annotations

import datetime as dt

from ..core.config import settings
from .base import JobContext

URL = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo"


async def rest_days(ctx: JobContext, year: int) -> dict:
    return await ctx.get_json("KASI", "getRestDeInfo", URL, dict(
        serviceKey=settings().data_go_kr_key, solYear=year, numOfRows=100, pageNo=1, _type="json"))


def parse_rest_days(body: dict) -> list[dict]:
    items = ((body.get("response") or {}).get("body") or {}).get("items") or {}
    it = items.get("item") if isinstance(items, dict) else None
    rows = it if isinstance(it, list) else [it] if isinstance(it, dict) else []
    out = []
    for r in rows:
        if str(r.get("isHoliday")) != "Y":
            continue
        try:
            day = dt.datetime.strptime(str(r["locdate"]), "%Y%m%d").date()
        except (KeyError, ValueError):
            continue
        out.append(dict(day=day, name=str(r.get("dateName") or "").strip()[:40] or "공휴일", kind=r.get("dateKind")))
    return out
