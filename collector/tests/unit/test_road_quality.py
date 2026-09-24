import datetime as dt

from roadrail.analytics.road_quality import Segment, aggregate_corridor, classify, despike
from roadrail.core.timeutil import KST


def test_mixed_flow_is_rest_stop_contamination():
    # 9.5km 구간: 가장 빠른 차 6분(95km/h), 평균 20분 → 흐르는 차와 멈춘 차가 섞임 → SUSPECT
    assert classify(9.5, 20 * 60, 4, 6 * 60) == "SUSPECT"


def test_real_congestion_even_fastest_is_slow_is_ok():
    # 2026-09-24 추석 귀성길 실측: 북천안→천안 9.5km 평균 21.9분 · 최소 19.8분 · 차량 4 → 정체로 인정
    assert classify(9.5, int(21.9 * 60), 4, int(19.8 * 60)) == "OK"
    # 차량이 많은 정체도 OK
    assert classify(13.0, 40 * 60, 400, 30 * 60) == "OK"


def test_classify_hard_limits():
    assert classify(10.0, 2 * 3600, 500) == "SUSPECT"   # 5 km/h
    assert classify(30.0, 60, 50) == "SUSPECT"           # 1800 km/h — 매칭 오류
    assert classify(20.0, 12 * 60, 3) == "OK"            # 100 km/h


def test_despike_drops_isolated_low_sample_spike_keeps_gradual_congestion():
    t = [dt.datetime(2026, 9, 24, 8, 0, tzinfo=KST) + dt.timedelta(minutes=5 * i) for i in range(9)]
    # 한 슬롯만 튄 값(표본 1대) → 제외
    vals = {k: (600, 3) for k in t}
    vals[t[4]] = (1800, 1)
    kept, dropped = despike(vals)
    assert dropped == 1 and t[4] not in kept and len(kept) == 8
    # 점진적으로 두 배가 되는 정체 → 유지
    ramp = {k: (600 + 100 * i, 3) for i, k in enumerate(t)}
    assert despike(ramp)[1] == 0
    # 표본이 많으면 튀어도 유지 (실제 사고 정체 가능성)
    vals[t[4]] = (1800, 40)
    assert despike(vals)[1] == 0


T0 = dt.datetime(2026, 10, 10, 17, 0, tzinfo=KST)
A = Segment("101", "103", 12.0)
B = Segment("103", "528", 6.0)
C = Segment("528", "106", 10.0)


def slots(n):
    return [T0 + dt.timedelta(minutes=5 * i) for i in range(n)]


def test_aggregate_all_observed_is_ok():
    ok = {A.key: {T0: 600}, B.key: {T0: 300}, C.key: {T0: 400}}
    stored, missing = aggregate_corridor([A, B, C], [T0], ok, {})
    assert missing == [] and stored[0].travel_sec == 1300 and stored[0].quality == "OK"
    assert (stored[0].observed, stored[0].total) == (3, 3)


def test_aggregate_fills_from_nearest_then_median_then_free_flow():
    t1 = T0 + dt.timedelta(minutes=5)
    ok = {A.key: {T0: 600, t1: 660}, B.key: {T0 - dt.timedelta(minutes=10): 330}, C.key: {}}
    med = {C.key: 450}
    stored, missing = aggregate_corridor([A, B, C], [t1], ok, med, min_observed_ratio=0.3)
    # A 실측 660 + B 최근접(15분 이내) 330 + C 중앙값 450
    assert stored[0].travel_sec == 660 + 330 + 450
    assert stored[0].quality == "FILLED" and stored[0].observed == 1
    stored2, _ = aggregate_corridor([A, B, C], [t1], ok, {}, min_observed_ratio=0.3)
    assert stored2[0].travel_sec == 660 + 330 + C.free_flow_sec  # 10km / 100km/h = 360초


def test_aggregate_low_coverage_is_missing():
    ok = {A.key: {T0: 600}}
    stored, missing = aggregate_corridor([A, B, C], slots(2), ok, {})
    assert stored == [] and missing == slots(2)
