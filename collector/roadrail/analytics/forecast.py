"""통행시간 예측 모델 (6-2). Java api 의 ForecastModels 와 **같은 식**이며,
fixtures/forecast_cases.json 골든 케이스를 양쪽 테스트가 공유한다 (언어 간 계약).

  M0 기준선      = baseline_p50(dow(target), slot(target)); 표본 n < 4 이면 전체 요일(dow=0) 기준선
  M1 기준선+편차 = M0(target) + (current − M0(current_slot)) × exp(−h / τ),  τ = 90분
  지속           = current
h 는 '마지막 관측 슬롯 → 목표 시각' 분. 도로공사 공개 지연(약 3시간) 때문에 departIn=0 이어도 h ≈ 180 이다.
"""
from __future__ import annotations

import datetime as dt
import math
from collections.abc import Mapping

from ..core.timeutil import iso_dow, slot_idx

MODEL_VERSION = "F-v1"
MIN_N = 4

# baseline[(dow, slot_idx)] = (p50_sec, n)
Baseline = Mapping[tuple[int, int], tuple[int, int]]


def m0(baseline: Baseline, target: dt.datetime) -> int | None:
    key = (iso_dow(target), slot_idx(target))
    v = baseline.get(key)
    if v and v[1] >= MIN_N:
        return v[0]
    alt = baseline.get((0, slot_idx(target)))
    if alt and alt[1] >= MIN_N:
        return alt[0]
    if v:  # 표본이 적더라도 요일 기준선밖에 없으면 사용
        return v[0]
    return alt[0] if alt else None


def m1(baseline: Baseline, current_slot: dt.datetime, current_sec: int, target: dt.datetime,
       tau_min: float = 90.0) -> int | None:
    b_target = m0(baseline, target)
    b_now = m0(baseline, current_slot)
    if b_target is None or b_now is None:
        return None
    h = max((target - current_slot).total_seconds() / 60.0, 0.0)
    return round_half_up(b_target + (current_sec - b_now) * math.exp(-h / tau_min))


def round_half_up(x: float) -> int:
    """Java Math.round 와 같은 반올림 (Python round 는 은행가 반올림이라 .5 에서 달라질 수 있음)."""
    return int(math.floor(x + 0.5))


def persistence(current_sec: int) -> int:
    return current_sec


def predict_all(baseline: Baseline, current_slot: dt.datetime, current_sec: int, target: dt.datetime,
                tau_min: float = 90.0) -> dict[str, int | None]:
    return {"M0": m0(baseline, target), "M1": m1(baseline, current_slot, current_sec, target, tau_min),
            "persistence": persistence(current_sec)}
