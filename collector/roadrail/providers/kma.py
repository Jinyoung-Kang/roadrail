"""기상청 단기예보 (VilageFcstInfoService_2.0) 어댑터 + 위경도 → 격자 변환 (FR-104)."""
from __future__ import annotations

import datetime as dt
import math

from ..core.config import settings
from ..core.timeutil import KST, parse_ymd_hm
from .base import JobContext

BASE = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0"
KEEP = {"TMP", "POP", "PTY", "SKY", "PCP"}
BASE_HOURS = (2, 5, 8, 11, 14, 17, 20, 23)

# 기상청 동네예보 격자 (Lambert Conformal Conic) 상수 — 기상청 제공 변환 코드와 동일
RE, GRID, SLAT1, SLAT2, OLON, OLAT, XO, YO = 6371.00877, 5.0, 30.0, 60.0, 126.0, 38.0, 43, 136


def latlon_to_grid(lat: float, lon: float) -> tuple[int, int]:
    d = math.pi / 180.0
    re = RE / GRID
    slat1, slat2, olon, olat = SLAT1 * d, SLAT2 * d, OLON * d, OLAT * d
    sn = math.tan(math.pi * 0.25 + slat2 * 0.5) / math.tan(math.pi * 0.25 + slat1 * 0.5)
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(sn)
    sf = math.tan(math.pi * 0.25 + slat1 * 0.5)
    sf = math.pow(sf, sn) * math.cos(slat1) / sn
    ro = math.tan(math.pi * 0.25 + olat * 0.5)
    ro = re * sf / math.pow(ro, sn)
    ra = math.tan(math.pi * 0.25 + lat * d * 0.5)
    ra = re * sf / math.pow(ra, sn)
    theta = lon * d - olon
    if theta > math.pi:
        theta -= 2.0 * math.pi
    if theta < -math.pi:
        theta += 2.0 * math.pi
    theta *= sn
    x = math.floor(ra * math.sin(theta) + XO + 0.5)
    y = math.floor(ro - ra * math.cos(theta) + YO + 0.5)
    return int(x), int(y)


def latest_base(now: dt.datetime) -> dt.datetime:
    """발표 후 10분부터 제공 → now-15분 이전의 가장 최근 발표 시각."""
    t = (now - dt.timedelta(minutes=15)).astimezone(KST)
    hours = [h for h in BASE_HOURS if h <= t.hour]
    if hours:
        return t.replace(hour=hours[-1], minute=0, second=0, microsecond=0)
    prev = t - dt.timedelta(days=1)
    return prev.replace(hour=23, minute=0, second=0, microsecond=0)


def parse_vilage(body: dict) -> list[dict]:
    items = (((body.get("response") or {}).get("body") or {}).get("items") or {}).get("item") or []
    out = []
    for i in items:
        if i.get("category") not in KEEP:
            continue
        out.append(dict(base_at=parse_ymd_hm(i["baseDate"], i["baseTime"]),
                        fcst_at=parse_ymd_hm(i["fcstDate"], i["fcstTime"]),
                        nx=int(i["nx"]), ny=int(i["ny"]), category=i["category"], value=str(i["fcstValue"])[:12]))
    return out


async def vilage(ctx: JobContext, base: dt.datetime, nx: int, ny: int) -> dict:
    return await ctx.get_json("KMA", "getVilageFcst", f"{BASE}/getVilageFcst", dict(
        serviceKey=settings().data_go_kr_key, pageNo=1, numOfRows=1000, dataType="JSON",
        base_date=base.strftime("%Y%m%d"), base_time=base.strftime("%H%M"), nx=nx, ny=ny))
