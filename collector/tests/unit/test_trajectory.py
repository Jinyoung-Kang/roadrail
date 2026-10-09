import datetime as dt

from roadrail.analytics.trajectory import SegmentSeries, floor5, trajectory_sec
from roadrail.core.timeutil import KST

T0 = dt.datetime(2026, 10, 1, 8, 0, tzinfo=KST)


def flat(sec: int, hours: int = 6) -> SegmentSeries:
    return SegmentSeries({T0 + dt.timedelta(minutes=5 * i): sec for i in range(hours * 12)})


def test_steady_traffic_is_plain_sum():
    assert trajectory_sec([flat(600), flat(900)], T0) == 1500


def test_uses_exit_slot_of_each_segment_not_departure_slot():
    # 구간1: 08:10 도착 슬롯부터 정체(20분). 08:00 출발 → 10분이면 08:10 도착 슬롯 → 20분 → 08:20 슬롯도 20분 → 20분이 고정점
    vals = {T0 + dt.timedelta(minutes=5 * i): (600 if i < 2 else 1200) for i in range(72)}
    assert trajectory_sec([SegmentSeries(vals)], T0) == 1200
    # 같은 슬롯 합(출발 슬롯 값)은 10분 — 궤적 합은 정체가 쌓이는 동안 더 길다
    assert SegmentSeries(vals).at(T0) == 600


def test_second_segment_starts_when_first_ends():
    # 구간2 는 08:30 도착부터 느려진다 — 구간1(15분) 뒤 08:15 에 들어가 10분이면 08:25 도착 → 아직 빠름
    seg2 = {T0 + dt.timedelta(minutes=5 * i): (600 if i < 6 else 1800) for i in range(72)}
    assert trajectory_sec([flat(900), SegmentSeries(seg2)], T0) == 1500
    # 08:05 출발 → 구간2 에 08:20 진입 → 08:30 도착 슬롯은 30분 → 08:50 도 30분 → 45분
    assert trajectory_sec([flat(900), SegmentSeries(seg2)], T0 + dt.timedelta(minutes=5)) == 2700


def test_missing_values_use_nearest_within_ten_minutes_else_none():
    sparse = SegmentSeries({T0: 600, T0 + dt.timedelta(minutes=30): 600})
    assert sparse.at(T0 + dt.timedelta(minutes=10)) == 600       # 10분 안
    assert sparse.at(T0 + dt.timedelta(minutes=15)) is None      # 양쪽 다 15분 떨어짐
    assert trajectory_sec([flat(600), SegmentSeries({})], T0) is None


def test_floor5():
    assert floor5(T0 + dt.timedelta(minutes=7, seconds=59)) == T0 + dt.timedelta(minutes=5)
