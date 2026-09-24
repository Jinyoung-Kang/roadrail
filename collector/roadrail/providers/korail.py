"""한국철도공사 열차운행정보 v2 (apis.data.go.kr/B551457/run/v2) 어댑터.

W1 스모크로 확정 (U-1, U-4)
- 오퍼레이션: travelerTrainRunPlan2 (운행계획: 시발·종착 계획 시각), travelerTrainRunInfo2 (역별 실제 출발·도착)
- 필터: cond[run_ymd::EQ]=YYYYMMDD, cond[trn_no::EQ]=… (ODcloud 형식). numOfRows 10,000 까지 한 번에 받음
- 운행정보 시각은 실제 시각 (1번 열차 계획 05:13 → 운행정보 05:14 출발)
- 조회 가능 기간: 계획·운행정보 모두 약 3개월 전 ~ 어제. **향후 운행계획은 제공되지 않음** (U-6, 기획서 가정과 다름)
"""
from __future__ import annotations

import datetime as dt

from ..core.config import settings
from ..core.timeutil import parse_korail_dt
from .base import JobContext

BASE = "https://apis.data.go.kr/B551457/run/v2"
PAGE = 10000


def _items(body: dict) -> tuple[list[dict], int]:
    b = (body.get("response") or {}).get("body") or {}
    items = (b.get("items") or {}).get("item") or [] if isinstance(b.get("items"), dict) else []
    if isinstance(items, dict):
        items = [items]
    return items, int(b.get("totalCount") or 0)


def parse_plan(body: dict) -> tuple[list[dict], int]:
    items, total = _items(body)
    out = []
    for i in items:
        dep, arr = parse_korail_dt(i.get("trn_plan_dptre_dt")), parse_korail_dt(i.get("trn_plan_arvl_dt"))
        if not (dep and arr):
            continue
        out.append(dict(run_ymd=dt.datetime.strptime(i["run_ymd"], "%Y%m%d").date(), trn_no=i["trn_no"],
                        dep_stn_cd=i["dptre_stn_cd"], arr_stn_cd=i["arvl_stn_cd"],
                        dep_stn_nm=i.get("dptre_stn_nm"), arr_stn_nm=i.get("arvl_stn_nm"),
                        plan_dep_at=dep, plan_arr_at=arr))
    return out, total


def parse_info(body: dict) -> tuple[list[dict], int]:
    items, total = _items(body)
    out = []
    for i in items:
        out.append(dict(run_ymd=dt.datetime.strptime(i["run_ymd"], "%Y%m%d").date(), trn_no=i["trn_no"],
                        run_seq=int(i["trn_run_sn"]), stn_cd=i["stn_cd"], stn_nm=i.get("stn_nm"),
                        line_cd=i.get("mrnt_cd"), line_nm=i.get("mrnt_nm"), updown_cd=i.get("uppln_dn_se_cd"),
                        stop_type_cd=i.get("stop_se_cd"), arr_at=parse_korail_dt(i.get("trn_arvl_dt")),
                        dep_at=parse_korail_dt(i.get("trn_dptre_dt"))))
    return out, total


async def _page(ctx: JobContext, op: str, day: dt.date, page: int) -> dict:
    return await ctx.get_json("KORAIL", op, f"{BASE}/{op}", {
        "serviceKey": settings().data_go_kr_key, "pageNo": page, "numOfRows": PAGE, "returnType": "json",
        "cond[run_ymd::EQ]": day.strftime("%Y%m%d"),
    })


async def fetch_plan(ctx: JobContext, day: dt.date) -> list[dict]:
    return await _fetch_all(ctx, "travelerTrainRunPlan2", day, parse_plan)


async def fetch_info(ctx: JobContext, day: dt.date) -> list[dict]:
    return await _fetch_all(ctx, "travelerTrainRunInfo2", day, parse_info)


async def _fetch_all(ctx: JobContext, op: str, day: dt.date, parser) -> list[dict]:
    rows, page = [], 1
    while True:
        items, total = parser(await _page(ctx, op, day, page))
        rows.extend(items)
        if page * PAGE >= total or not items:
            return rows
        page += 1
