"""한국도로공사 (data.ex.co.kr) 어댑터.

W1 스모크로 확정한 사항 (docs/W1-smoke.md)
- 영업소 간 통행시간: trtm/realUnitTrtm (iStartUnitCode, iEndUnitCode). 기획서의 upDownTrafficTime 경로는 404 (U-2)
  · 응답은 **오늘 하루 전체**(stdDate=오늘)를 차종 → stdTime 순으로 정렬해 페이지로 준다. numOfRows 최대 99
  · stdTime 'HH:MM' = 도착기준 5분 슬롯, 'HH  ' = 시간 집계 행(사용 안 함)
  · timeAvg/timeMin/timeMax 단위는 분, efcvTrfl = 표본 차량 수
  · 공개 지연이 약 3시간 (21:00 에 최신 슬롯 17:55) — 그래서 5분마다 부를 필요가 없다 (ADR-008)
- 영업소 좌표: locationinfo/locationinfoUnit (xValue=경도, yValue=위도). basicinfo/unitList 에는 좌표가 없음
- 전국 교통량: trafficapi/trafficAll tmType=2 → 최근 1시간의 15분 집계 4개 슬롯 (약 80분 지연)
- 문자 안내: burstInfo/realTimeSms
"""
from __future__ import annotations

import datetime as dt
import hashlib
from dataclasses import dataclass

from ..core.timeutil import KST, parse_ymd_hm
from .base import JobContext

BASE = "https://data.ex.co.kr/openapi"
PAGE = 99
CAR_TYPE = "1"  # 1종(소형차) — 승용차 이동 판단 기준


@dataclass(frozen=True)
class TravelRow:
    slot_ts: dt.datetime
    start: str
    end: str
    car_type: str
    avg_sec: int
    min_sec: int | None
    max_sec: int | None
    vehicles: int | None


def _min_to_sec(v) -> int | None:
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return int(round(x * 60)) if x > 0 else None


def parse_travel_page(body: dict, car_type: str = CAR_TYPE) -> tuple[list[TravelRow], int, int, bool]:
    """→ (차종 car_type 의 5분 행, 페이지의 car_type 행 수(시간 집계 포함), pageSize, 이 페이지 뒤에 car_type 행이 더 있을 수 있는지)."""
    items = body.get("realUnitTrtmVO") or []
    out, n_type = [], 0
    for it in items:
        if (it.get("tcsCarTypeCode") or "").strip() != car_type:
            continue
        n_type += 1
        st = (it.get("stdTime") or "").strip()
        if ":" not in st:
            continue  # 'HH' 시간 집계 행
        avg = _min_to_sec(it.get("timeAvg"))
        if not avg:
            continue
        try:
            veh = int(it.get("efcvTrfl")) if it.get("efcvTrfl") not in (None, "") else None
        except ValueError:
            veh = None
        out.append(TravelRow(
            slot_ts=parse_ymd_hm(it["stdDate"], st), start=it["startUnitCode"].strip(), end=it["endUnitCode"].strip(),
            car_type=car_type, avg_sec=avg, min_sec=_min_to_sec(it.get("timeMin")),
            max_sec=_min_to_sec(it.get("timeMax")), vehicles=veh,
        ))
    page_size = int(body.get("pageSize") or 0)
    page_no = int(body.get("pageNo") or 1)
    last_is_type = bool(items) and (items[-1].get("tcsCarTypeCode") or "").strip() == car_type
    more = last_is_type and page_no < page_size
    return out, n_type, page_size, more


async def travel_page(ctx: JobContext, start: str, end: str, page: int) -> dict:
    return await ctx.get_json("EX", "trtm/realUnitTrtm", f"{BASE}/trtm/realUnitTrtm", dict(
        key=_key(), type="json", iStartUnitCode=start, iEndUnitCode=end, numOfRows=PAGE, pageNo=page))


def parse_units(body: dict) -> list[dict]:
    out = []
    for u in body.get("list") or []:
        try:
            lat, lon = float(u["yValue"]), float(u["xValue"])
        except (TypeError, ValueError, KeyError):
            lat = lon = None
        out.append(dict(unit_code=u["unitCode"].strip(), unit_name=u["unitName"].strip(),
                        route_no=(u.get("routeNo") or "").strip() or None,
                        route_name=(u.get("routeName") or "").strip() or None, lat=lat, lon=lon))
    return out


async def units_page(ctx: JobContext, page: int) -> dict:
    return await ctx.get_json("EX", "locationinfo/locationinfoUnit", f"{BASE}/locationinfo/locationinfoUnit",
                              dict(key=_key(), type="json", numOfRows=PAGE, pageNo=page))


def parse_traffic_all(body: dict) -> list[dict]:
    out = []
    for r in body.get("trafficAll") or []:
        try:
            vol = int(r["trafficAmout"])  # 원문 오타 그대로 (trafficAmout)
            slot = parse_ymd_hm(r["sumDate"], r["sumTm"])
        except (KeyError, ValueError, TypeError):
            continue
        out.append(dict(slot_ts=slot, ex_div_code=r.get("exDivCode") or "", tcs_type=r.get("tcsType") or "",
                        car_type=r.get("carType") or "", volume=vol))
    return out


async def traffic_all(ctx: JobContext) -> dict:
    return await ctx.get_json("EX", "trafficapi/trafficAll", f"{BASE}/trafficapi/trafficAll",
                              dict(key=_key(), type="json", tmType="2"))


def parse_sms(body: dict) -> list[dict]:
    out = []
    for r in body.get("realTimeSMSList") or []:
        text = (r.get("smsText") or "").strip()
        if not text:
            continue
        try:
            d = dt.datetime.strptime(f"{r['accDate']} {r['accHour']}", "%Y.%m.%d %H:%M:%S").replace(tzinfo=KST)
        except (KeyError, ValueError, TypeError):
            continue
        h = hashlib.sha256(f"{r.get('accDate')}|{r.get('accHour')}|{r.get('roadNM')}|{text}".encode()).hexdigest()
        out.append(dict(msg_hash=h, sent_at=d, type_code=(r.get("accTypeCode") or "").strip() or None,
                        type_name=(r.get("accType") or "").strip() or None,
                        route_no=(r.get("nosunNM") or "").strip() or None,
                        route_name=(r.get("roadNM") or "").strip() or None,
                        direction_txt=(r.get("startEndTypeCode") or "").strip() or None,
                        process_name=(r.get("accProcessNM") or "").strip() or None, content=text))
    return out


async def sms_page(ctx: JobContext, page: int) -> dict:
    return await ctx.get_json("EX", "burstInfo/realTimeSms", f"{BASE}/burstInfo/realTimeSms",
                              dict(key=_key(), type="json", numOfRows=PAGE, pageNo=page))


def _key() -> str:
    from ..core.config import settings
    return settings().ex_api_key
