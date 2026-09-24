"""에어코리아 시도별 실시간 측정정보 (getCtprvnRltmMesureDnsty).

개발계정 하루 500건 (E12) → 측정소별이 아니라 시도별 1회로 해당 시도 측정소를 한꺼번에 받는다.
"""
from __future__ import annotations

import datetime as dt

from ..core.config import settings
from ..core.timeutil import KST
from .base import JobContext

URL = "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty"


def _int(v) -> int | None:
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return None  # '-' (통신장애·점검) 등


def parse_sido(body: dict) -> list[dict]:
    items = ((body.get("response") or {}).get("body") or {}).get("items") or []
    out = []
    for i in items:
        t = i.get("dataTime")
        if not t or not i.get("stationName"):
            continue  # 측정소 점검 등으로 시각이 비어 있는 행 (2026-09-24 실측)
        try:
            # '2026-09-24 24:00' 형식이 올 수 있음 → 다음날 00:00
            if t.endswith("24:00"):
                ts = dt.datetime.strptime(t[:10], "%Y-%m-%d").replace(tzinfo=KST) + dt.timedelta(days=1)
            else:
                ts = dt.datetime.strptime(t, "%Y-%m-%d %H:%M").replace(tzinfo=KST)
        except (KeyError, ValueError, TypeError):
            continue
        out.append(dict(data_time=ts, station_name=i["stationName"], sido_name=i["sidoName"],
                        pm10=_int(i.get("pm10Value")), pm25=_int(i.get("pm25Value")),
                        khai_value=_int(i.get("khaiValue")), khai_grade=_int(i.get("khaiGrade")),
                        pm25_grade=_int(i.get("pm25Grade"))))
    return out


async def sido(ctx: JobContext, sido_name: str) -> dict:
    return await ctx.get_json("AIRKOREA", "getCtprvnRltmMesureDnsty", URL, dict(
        serviceKey=settings().data_go_kr_key, pageNo=1, numOfRows=200, returnType="json",
        sidoName=sido_name, ver="1.0"))
