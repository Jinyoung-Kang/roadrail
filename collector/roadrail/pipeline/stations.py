"""기차역 좌표 보정 (FR-103). 운행정보에서 새로 나온 역(좌표 없음)을 카카오 키워드 검색으로 찾는다.

규칙: '{역명}역' 으로 검색해 카테고리가 기차역(없으면 전철역)이고 괄호·공백을 뺀 장소명이 '{역명}역' 으로
시작하는 첫 결과. '판교(경기)' 처럼 지역 괄호가 있으면 주소로 구분한다.
찾지 못하면 geocode_note 에 사유를 남기고 다음 주에 다시 시도한다 (호출 수 = 좌표 없는 역 수).
"""
from __future__ import annotations

import logging
import re

from ..core import db
from ..providers import kakao
from ..providers.base import JobContext, ProviderError

logger = logging.getLogger(__name__)

ALIASES = {"여수EXPO": "여수엑스포"}
_PAREN = re.compile(r"[()\s]")


def normalize(name: str) -> str:
    return _PAREN.sub("", name)


def split_hint(stn_nm: str) -> tuple[str, str | None]:
    """'판교(경기)' → ('판교', '경기') — 같은 이름 역을 지역으로 구분"""
    m = re.match(r"^(.+?)\((.+)\)$", stn_nm)
    return (m.group(1), m.group(2)) if m else (stn_nm, None)


def pick_station(body: dict, name: str) -> tuple[float, float] | None:
    """카테고리가 기차역(우선) 또는 전철역이고, 괄호·공백을 뺀 장소명이 '{역명}역' 으로 시작하는 첫 결과.
    역명에 지역 괄호가 있으면 주소에 그 지역이 들어간 결과만."""
    base, hint = split_hint(ALIASES.get(name, name))
    want = normalize(base) + "역"
    docs = body.get("documents") or []
    for kinds in (("기차역",), ("전철", "지하철")):
        for d in docs:
            cat = d.get("category_name") or ""
            if not any(k in cat for k in kinds):
                continue
            if not normalize(d.get("place_name") or "").startswith(want):
                continue
            if hint and hint not in (d.get("address_name") or "") + (d.get("road_address_name") or ""):
                continue
            return float(d["y"]), float(d["x"])
    # 마지막: 기차역 분류이면서 역명으로 시작 ('진부(오대산)역' 처럼 부기가 붙은 역명)
    for d in docs:
        if "기차역" in (d.get("category_name") or "") and normalize(d.get("place_name") or "").startswith(normalize(base)):
            return float(d["y"]), float(d["x"])
    return None


async def geocode_stations(ctx: JobContext, limit: int = 400) -> int:
    rows = await db.fetch("""SELECT stn_cd, stn_nm FROM ref.station
                             WHERE lat IS NULL AND (geocoded_at IS NULL OR geocoded_at < now() - interval '6 days')
                             ORDER BY stn_cd LIMIT %s""", (limit,))
    found = 0
    for r in rows:
        try:
            base, hint = split_hint(ALIASES.get(r["stn_nm"], r["stn_nm"]))
            body = await kakao.keyword(ctx, f"{hint + ' ' if hint else ''}{base}역")
        except ProviderError as e:
            ctx.notes.append(f"PARTIAL: {r['stn_nm']} {e}")
            continue
        ll = pick_station(body, r["stn_nm"])
        await db.execute("""UPDATE ref.station SET lat = %s, lon = %s, source = CASE WHEN %s THEN 'KAKAO' ELSE source END,
                            geocoded_at = now(), geocode_note = %s WHERE stn_cd = %s""",
                         (ll[0] if ll else None, ll[1] if ll else None, ll is not None,
                          None if ll else "기차역 검색 결과 없음", r["stn_cd"]))
        found += 1 if ll else 0
    ctx.rows += found
    ctx.note(f"역 좌표 보정 {found}/{len(rows)}")
    return found
