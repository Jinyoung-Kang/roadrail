"""경찰청 도시교통정보센터(UTIC) 돌발정보 — 전국 사고 · 공사 · 행사 · 통제 (일반 도로 포함). XML, HTTPS.

응답 목록 = 지금 진행 중인 돌발. 좌표는 경위도(WGS84, locationDataX = 경도). 시각은 '2026년 09월 28일  17시 30분' 형식(KST).
도로공사 문자 안내(고속도로만)와 같은 표(ts.road_incident)에 source='UTIC' 로 넣는다 — 겹치는 고속도로 건은 조회할 때 하나만 남긴다.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import re
import xml.etree.ElementTree as ET

from ..core.config import settings
from ..core.timeutil import KST
from .base import JobContext

URL = "https://www.utic.go.kr/guide/imsOpenData.do"   # HTTP 로는 다른 응답이 온다(실측) — HTTPS 만
# incidenteTypeCd — 1 · 2 · 5 는 실제 응답의 제목 머리([사고] · [공사] · [통제])로 확인. 모르는 코드는 제목 머리를 쓴다
TYPE_NAME = {"1": "사고", "2": "공사", "3": "행사", "4": "기상", "5": "통제", "6": "재난", "7": "기타"}
_TIME = re.compile(r"(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일\s*(\d{1,2})\s*시\s*(\d{1,2})\s*분")
_TAG = re.compile(r"^\s*\[([^\]]{1,10})\]")
_EDGE = re.compile(r"^[\s*,·.]+|[\s*,·.]+$")   # 입력자가 붙인 앞뒤 기호 (실측: '*관문대로,') — 도로명에서만 걷어 낸다


async def incidents(ctx: JobContext) -> ET.Element:
    return await ctx.get_xml("UTIC", "imsOpenData", URL, {"key": settings().utic_api_key}, root="result")


def parse_time(s: str | None) -> dt.datetime | None:
    m = _TIME.search(s or "")
    if not m:
        return None
    try:
        return dt.datetime(*(int(g) for g in m.groups()), tzinfo=KST)
    except ValueError:
        return None


def _clip(s: str | None, n: int) -> str | None:
    s = (s or "").strip()
    return s[:n] if s else None


def parse_incidents(root: ET.Element) -> list[dict]:
    """record → road_incident 행. 식별자 · 시각이 없는 행은 버리고, 대한민국 밖 좌표는 좌표 없음으로 둔다(위치를 추정하지 않음)."""
    out: list[dict] = []
    for rec in root.iter("record"):
        f = {c.tag: (c.text or "").strip() for c in rec}
        iid = f.get("incidentId")
        start = parse_time(f.get("startDate")) or parse_time(f.get("updateDate"))
        if not iid or start is None:
            continue
        try:
            lon, lat = float(f.get("locationDataX", "")), float(f.get("locationDataY", ""))
        except ValueError:
            lon = lat = None
        if lat is None or lon is None or not (32.5 <= lat <= 39.0 and 124.0 <= lon <= 132.0):
            lat = lon = None
        title = f.get("incidentTitle") or ""
        code = f.get("incidenteTypeCd") or ""
        tag = _TAG.match(title)
        name = TYPE_NAME.get(code) or (tag.group(1) if tag else "돌발")
        out.append({
            "msg_hash": hashlib.sha256(f"UTIC:{iid}".encode()).hexdigest(),
            "sent_at": start,
            "type_code": ("U" + code)[:4],
            "type_name": name[:20],
            "route_name": _clip(_EDGE.sub("", f.get("roadName") or ""), 40),
            "content": title or name,
            "lat": lat,
            "lon": lon,
            "point_name": _clip(f.get("addressNew") or f.get("addressJibun"), 120),
            "end_at": parse_time(f.get("endDate")),
            "lane": _clip(f.get("lane"), 40),
        })
    return out
