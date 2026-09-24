import datetime as dt

from roadrail.analytics.punctuality import corridor_trip, train_punctuality
from roadrail.core.timeutil import KST

D = dt.date(2026, 9, 23)


def t(h, m, day=23):
    return dt.datetime(2026, 9, day, h, m, tzinfo=KST)


PLAN = dict(run_ymd=D, trn_no="00001", dep_stn_cd="SEL", arr_stn_cd="BSN", plan_dep_at=t(5, 13), plan_arr_at=t(7, 50))


def stop(seq, stn, arr=None, dep=None):
    return dict(run_ymd=D, trn_no="00001", run_seq=seq, stn_cd=stn, arr_at=arr, dep_at=dep)


STOPS = [stop(1, "SEL", dep=t(5, 14)), stop(2, "DJN", arr=t(6, 12), dep=t(6, 15)),
         stop(3, "DGU", arr=t(7, 0), dep=t(7, 2)), stop(4, "BSN", arr=t(7, 57))]


def test_p_v1_exact_delays():
    tp = train_punctuality(PLAN, STOPS, threshold_min=5)
    assert tp.dep_delay_min == 1.0 and tp.arr_delay_min == 7.0
    assert tp.on_time is False and tp.status == "OK"


def test_early_arrival_negative_is_kept():
    stops = STOPS[:-1] + [stop(4, "BSN", arr=t(7, 47))]
    tp = train_punctuality(PLAN, stops)
    assert tp.arr_delay_min == -3.0 and tp.on_time is True


def test_missing_terminal_row_is_unverified():
    tp = train_punctuality(PLAN, STOPS[:-1])
    assert tp.status == "UNVERIFIED" and tp.on_time is None and tp.arr_delay_min is None


def test_train_crossing_midnight():
    plan = dict(PLAN, plan_dep_at=t(23, 30), plan_arr_at=t(1, 40, day=24))
    stops = [stop(1, "SEL", dep=t(23, 32)), stop(2, "BSN", arr=t(1, 43, day=24))]
    tp = train_punctuality(plan, stops)
    assert tp.dep_delay_min == 2.0 and tp.arr_delay_min == 3.0 and tp.on_time is True


def test_corridor_trip_origin_exact_intermediate_interpolated():
    tp = train_punctuality(PLAN, STOPS)
    ct = corridor_trip(STOPS, "SEL", "DJN", tp)
    assert ct.dep_basis == "EXACT" and ct.dep_delay_min == 1.0
    # 06:12 도착은 운행(05:14→07:57, 163분) 중 58분 경과 → 1 + (7-1)×58/163 = 3.1
    assert ct.arr_basis == "EST" and ct.arr_delay_min == 3.1
    assert ct.ride_min == 58.0 and ct.on_time is True
    assert ct.est_plan_arr_at == t(6, 12) - dt.timedelta(minutes=3.1)


def test_corridor_trip_terminal_exact():
    tp = train_punctuality(PLAN, STOPS)
    ct = corridor_trip(STOPS, "DJN", "BSN", tp)
    assert ct.arr_basis == "EXACT" and ct.arr_delay_min == 7.0 and ct.on_time is False


def test_corridor_trip_wrong_order_or_missing():
    tp = train_punctuality(PLAN, STOPS)
    assert corridor_trip(STOPS, "DJN", "SEL", tp) is None
    assert corridor_trip(STOPS, "SEL", "GNG", tp) is None


def test_corridor_trip_without_plan_has_no_delay():
    ct = corridor_trip(STOPS, "SEL", "DJN", None)
    assert ct.dep_basis == ct.arr_basis == "NONE" and ct.on_time is None and ct.ride_min == 58.0
