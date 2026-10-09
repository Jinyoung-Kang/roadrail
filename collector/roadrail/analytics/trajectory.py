"""궤적 합 (T-v1) — 출발 시각 t 에 길의 첫 영업소를 떠난 차가 실제로 달렸을 시간의 근사 (ADR-029, 3단계).

길 소요(ts.road_corridor_tt)는 '같은 도착 슬롯의 구간 값 합'이라, 정체가 쌓이거나 풀리는 동안 한 대의 차가 달린 시간과 다르다.
궤적 합은 구간을 차례로 따라간다: 구간 i 에 τ 에 들어간 차는 τ + s 에 나오고, 원천 슬롯은 **도착(진출) 기준**이므로
s = 값(floor5(τ + s)) 를 만족하는 s 를 고정점 반복으로 찾는다(시작값은 τ 슬롯 값, 최대 4번). 다음 구간은 τ + s 에서 시작한다.

값은 1종 · 품질 OK · 튀는 값 제거(H-v1) 뒤의 구간 값. 그 슬롯 값이 없으면 ±10분 안의 가장 가까운 슬롯, 그것도 없으면
그 출발은 계산하지 않는다(채워 넣지 않음 — 정답 데이터라 추정을 섞지 않는다). 카카오 예측의 오차를 재는 정답으로만 쓴다.
"""
from __future__ import annotations

import bisect
import datetime as dt
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

RULE = "T-v1"
SLOT = dt.timedelta(minutes=5)
NEAREST = dt.timedelta(minutes=10)
ITERATIONS = 4


def floor5(t: dt.datetime) -> dt.datetime:
    return t.replace(minute=t.minute - t.minute % 5, second=0, microsecond=0)


@dataclass(frozen=True)
class SegmentSeries:
    """한 구간의 {도착 슬롯: 초} — 정렬한 슬롯 목록으로 가장 가까운 값을 찾는다"""
    values: Mapping[dt.datetime, int]

    def __post_init__(self) -> None:
        object.__setattr__(self, "_keys", sorted(self.values))

    def at(self, t: dt.datetime) -> int | None:
        s = floor5(t)
        v = self.values.get(s)
        if v is not None:
            return v
        keys: list[dt.datetime] = self._keys  # type: ignore[attr-defined]
        i = bisect.bisect_left(keys, s)
        best = None
        for j in (i - 1, i):
            if 0 <= j < len(keys) and abs(keys[j] - s) <= NEAREST and (best is None or abs(keys[j] - s) < abs(best - s)):
                best = keys[j]
        return self.values[best] if best is not None else None


def trajectory_sec(chain: Sequence[SegmentSeries], depart: dt.datetime) -> int | None:
    """길의 구간 순서대로 따라간 소요(초). 어느 구간이든 값이 없으면 None"""
    tau = depart
    for seg in chain:
        s = seg.at(tau)
        if s is None:
            return None
        for _ in range(ITERATIONS):
            nxt = seg.at(tau + dt.timedelta(seconds=s))
            if nxt is None:
                return None
            if nxt == s:
                break
            s = nxt
        tau += dt.timedelta(seconds=s)
    return int((tau - depart).total_seconds())
