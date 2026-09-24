"""도로 구간 값 품질 규칙 (Q-v2) · 튀는 값 제거 (H-v1) · 길 합산.

W1 실측: 표본이 적은 구간의 timeAvg 가 휴게소 정차 차량 때문에 부풀려진다
(천안→대전 약 75km · 슬롯당 1대 · 100~218분). 반면 2026-09-24(추석 연휴 첫날) 실측에서는
하행 전 구간이 실제로 2~3배 느렸고 **가장 빠른 차량(timeMin)도 느렸다** — 이것은 정체이지 오류가 아니다.
Q-v1(속도 < 40km/h ∧ 차량 < 5 → SUSPECT)은 이 정체를 과하게 걸러서 Q-v2 로 바꿨다.

수집 시점 Q-v2 (행 단위)
  SUSPECT ⇔ 평균 속도 < 8 km/h ∨ > 170 km/h                       (이상치 · 매칭 오류)
          ∨ (차량 ≥ 2 ∧ 최소시간 속도 ≥ 60 km/h ∧ 평균 ≥ 2.5 × 최소)  (흐르는 차와 멈춘 차가 섞임 → 정차 혼입)
합산 시점 H-v1 (Hampel 형 필터, 구간별)
  같은 구간 ±30분 중앙값의 2배를 넘고 차량 < 5 인 고립된 값은 제외 (정체는 점진적으로 쌓이고, 정차 혼입은 한 슬롯만 튄다)

길 슬롯 값 = Σ 구간 값 (같은 도착 슬롯의 '순간 통행시간' 근사). 구간 값이 없거나 제외되면
  1) 같은 구간의 ±15분 안 값 중 가장 가까운 슬롯 → 2) 직전 24시간 중앙값 → 3) 자유속도 100 km/h 시간
순서로 채우고, 실측 구간 비율이 60% 미만인 슬롯은 저장하지 않는다(결측으로 기록).
"""
from __future__ import annotations

import datetime as dt
from collections.abc import Iterable, Mapping
from dataclasses import dataclass

RULE = "Q-v2"
DESPIKE_RULE = "H-v1"


def implied_speed_kmh(distance_km: float, travel_sec: int) -> float:
    return distance_km / (travel_sec / 3600.0) if travel_sec > 0 else float("inf")


def classify(distance_km: float, travel_sec: int, vehicles: int | None, min_sec: int | None = None, *,
             hard_min_speed=8.0, max_speed=170.0, free_min_speed=60.0, mix_ratio=2.5) -> str:
    v = implied_speed_kmh(distance_km, travel_sec)
    if v < hard_min_speed or v > max_speed:
        return "SUSPECT"
    if (vehicles or 0) >= 2 and min_sec and implied_speed_kmh(distance_km, min_sec) >= free_min_speed \
            and travel_sec >= mix_ratio * min_sec:
        return "SUSPECT"
    return "OK"


def despike(values: Mapping[dt.datetime, tuple[int, int | None]], window: dt.timedelta = dt.timedelta(minutes=30),
            ratio: float = 2.0, max_vehicles: int = 5) -> tuple[dict[dt.datetime, int], int]:
    """values[t] = (초, 차량 수) → (튀는 값을 뺀 {t: 초}, 제외 건수)."""
    keys = sorted(values)
    out, dropped = {}, 0
    for t in keys:
        sec, veh = values[t]
        near = sorted(values[k][0] for k in keys if abs(k - t) <= window)
        med = near[len(near) // 2] if len(near) % 2 else (near[len(near) // 2 - 1] + near[len(near) // 2]) / 2
        if len(near) >= 3 and sec > ratio * med and (veh or 0) < max_vehicles:
            dropped += 1
            continue
        out[t] = sec
    return out, dropped


@dataclass(frozen=True)
class Segment:
    start: str
    end: str
    distance_km: float

    @property
    def key(self) -> tuple[str, str]:
        return self.start, self.end

    @property
    def free_flow_sec(self) -> int:
        return int(round(self.distance_km / 100.0 * 3600))


@dataclass(frozen=True)
class CorridorSlot:
    slot_ts: dt.datetime
    travel_sec: int
    observed: int
    total: int
    quality: str  # OK | FILLED


def aggregate_corridor(
    segments: list[Segment],
    slots: Iterable[dt.datetime],
    ok_values: Mapping[tuple[str, str], Mapping[dt.datetime, int]],
    seg_median: Mapping[tuple[str, str], int],
    min_observed_ratio: float = 0.6,
    near_window: dt.timedelta = dt.timedelta(minutes=15),
) -> tuple[list[CorridorSlot], list[dt.datetime]]:
    """→ (저장할 길 슬롯, 결측 슬롯)."""
    stored: list[CorridorSlot] = []
    missing: list[dt.datetime] = []
    total = len(segments)
    for t in slots:
        s_sum, observed = 0, 0
        for seg in segments:
            vals = ok_values.get(seg.key) or {}
            v = vals.get(t)
            if v is not None:
                observed += 1
                s_sum += v
                continue
            near = [(abs((k - t).total_seconds()), val) for k, val in vals.items() if abs(k - t) <= near_window]
            if near:
                s_sum += min(near)[1]
            elif seg.key in seg_median:
                s_sum += seg_median[seg.key]
            else:
                s_sum += seg.free_flow_sec
        if total == 0 or observed / total < min_observed_ratio:
            missing.append(t)
            continue
        stored.append(CorridorSlot(t, s_sum, observed, total, "OK" if observed == total else "FILLED"))
    return stored, missing
