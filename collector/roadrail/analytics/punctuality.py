"""열차 정시성 (6-1).

P-v1 (정확): 운행계획(시발·종착 계획 시각) ↔ 운행정보(역별 실제 시각)
  출발 지연 = 시발역 실제 출발 − 계획 출발, 도착 지연 = 종착역 실제 도착 − 계획 도착
  해당 역 행이 없으면 '운행 확인 불가'(UNVERIFIED) → 정시율 분모에서 제외 (FR-303)
  정시 ⇔ 도착 지연 ≤ 임계값(기본 5분). 음수(조기 도착)는 그대로 저장

P-i1 (코리도 구간, ⚠ 추정): 중간역의 계획 시각은 API 에 없다 (E9).
  A역(출발)·B역(도착)이 시발·종착이면 P-v1 과 같은 정확 값(EXACT),
  중간역이면 시발 출발 지연 d0 와 종착 도착 지연 d1 사이를 실제 운행 경과시간 비율로 선형 보간(EST):
      d(S) = d0 + (d1 − d0) × (tS − t0) / (t1 − t0)
  지연이 운행 중 누적된다는 가정. 화면에는 ⚠ 추정으로 표시한다.
"""
from __future__ import annotations

import datetime as dt
from dataclasses import dataclass

RULE_EXACT = "P-v1"
RULE_CORRIDOR = "P-i1"


def minutes(delta: dt.timedelta) -> float:
    return round(delta.total_seconds() / 60.0, 1)


@dataclass
class TrainPunctuality:
    run_ymd: dt.date
    trn_no: str
    dep_stn_cd: str
    arr_stn_cd: str
    plan_dep_at: dt.datetime
    plan_arr_at: dt.datetime
    act_dep_at: dt.datetime | None
    act_arr_at: dt.datetime | None
    dep_delay_min: float | None
    arr_delay_min: float | None
    on_time: bool | None
    status: str


def train_punctuality(plan: dict, stops: list[dict], threshold_min: int = 5) -> TrainPunctuality:
    """plan: run_plan 행, stops: 같은 (run_ymd, trn_no) 의 run_info 행들."""
    dep_row = next((s for s in stops if s["stn_cd"] == plan["dep_stn_cd"] and s.get("dep_at")), None)
    arr_row = next((s for s in reversed(stops) if s["stn_cd"] == plan["arr_stn_cd"] and s.get("arr_at")), None)
    act_dep = dep_row["dep_at"] if dep_row else None
    act_arr = arr_row["arr_at"] if arr_row else None
    dep_delay = minutes(act_dep - plan["plan_dep_at"]) if act_dep else None
    arr_delay = minutes(act_arr - plan["plan_arr_at"]) if act_arr else None
    verified = arr_delay is not None
    return TrainPunctuality(
        run_ymd=plan["run_ymd"], trn_no=plan["trn_no"], dep_stn_cd=plan["dep_stn_cd"], arr_stn_cd=plan["arr_stn_cd"],
        plan_dep_at=plan["plan_dep_at"], plan_arr_at=plan["plan_arr_at"], act_dep_at=act_dep, act_arr_at=act_arr,
        dep_delay_min=dep_delay, arr_delay_min=arr_delay,
        on_time=(arr_delay <= threshold_min) if verified else None,
        status="OK" if verified else "UNVERIFIED",
    )


@dataclass
class CorridorTrip:
    run_ymd: dt.date
    trn_no: str
    act_dep_at: dt.datetime
    act_arr_at: dt.datetime
    est_plan_dep_at: dt.datetime | None
    est_plan_arr_at: dt.datetime | None
    dep_delay_min: float | None
    arr_delay_min: float | None
    dep_basis: str
    arr_basis: str
    ride_min: float
    on_time: bool | None


def corridor_trip(stops: list[dict], a: str, b: str, tp: TrainPunctuality | None,
                  threshold_min: int = 5) -> CorridorTrip | None:
    """열차가 A역 출발 → B역 도착 순서로 정차했으면 구간 운행 1건. 아니면 None."""
    stops = sorted(stops, key=lambda s: s["run_seq"])
    ia = next((i for i, s in enumerate(stops) if s["stn_cd"] == a and s.get("dep_at")), None)
    if ia is None:
        return None
    ib = next((i for i, s in enumerate(stops) if i > ia and s["stn_cd"] == b and s.get("arr_at")), None)
    if ib is None:
        return None
    t_a, t_b = stops[ia]["dep_at"], stops[ib]["arr_at"]

    def delay_at(station: str, t: dt.datetime) -> tuple[float | None, str]:
        if tp is None or tp.status != "OK" or tp.act_dep_at is None or tp.dep_delay_min is None:
            return None, "NONE"
        if station == tp.dep_stn_cd:
            return tp.dep_delay_min, "EXACT"
        if station == tp.arr_stn_cd:
            return tp.arr_delay_min, "EXACT"
        span = (tp.act_arr_at - tp.act_dep_at).total_seconds()
        if span <= 0:
            return None, "NONE"
        frac = min(max((t - tp.act_dep_at).total_seconds() / span, 0.0), 1.0)
        return round(tp.dep_delay_min + (tp.arr_delay_min - tp.dep_delay_min) * frac, 1), "EST"

    d_dep, dep_basis = delay_at(a, t_a)
    d_arr, arr_basis = delay_at(b, t_b)
    return CorridorTrip(
        run_ymd=stops[0]["run_ymd"], trn_no=stops[0]["trn_no"], act_dep_at=t_a, act_arr_at=t_b,
        est_plan_dep_at=(t_a - dt.timedelta(minutes=d_dep)) if d_dep is not None else None,
        est_plan_arr_at=(t_b - dt.timedelta(minutes=d_arr)) if d_arr is not None else None,
        dep_delay_min=d_dep, arr_delay_min=d_arr, dep_basis=dep_basis, arr_basis=arr_basis,
        ride_min=minutes(t_b - t_a), on_time=(d_arr <= threshold_min) if d_arr is not None else None,
    )
