"""계약 테스트: W1 스모크에서 저장한 실제 응답(fixtures/<provider>/*.json)의 필드 매핑 (10장).
응답 형식이 바뀌면 `python3 tools/smoke.py --save` 로 갱신하고 이 테스트가 무엇이 깨졌는지 알려 준다."""
import datetime as dt
import json

from roadrail.core.timeutil import KST
from roadrail.providers import airkorea, ex, kakao, kma, korail


def load(fixtures_dir, provider, name):
    return json.loads((fixtures_dir / provider / f"{name}.json").read_text())


def test_ex_travel_time(fixtures_dir):
    body = load(fixtures_dir, "ex", "travel_time")
    rows, n_type, page_size, more = ex.parse_travel_page(body)
    assert rows, "1종 5분 행이 있어야 함"
    r = rows[0]
    assert (r.start, r.end, r.car_type) == ("101", "103", "1")
    assert r.slot_ts.tzinfo is not None and r.slot_ts.minute % 5 == 0
    assert r.avg_sec > 0 and r.vehicles is not None
    assert n_type >= len(rows)  # 시간 집계 행('HH') 도 차종 행 수에는 포함
    assert page_size >= 1


def test_ex_units(fixtures_dir):
    units = ex.parse_units(load(fixtures_dir, "ex", "unit_location"))
    assert units and all(u["unit_code"] == u["unit_code"].strip() for u in units)
    assert any(u["lat"] and 33 < u["lat"] < 39 and 124 < u["lon"] < 132 for u in units)


def test_ex_traffic_all(fixtures_dir):
    rows = ex.parse_traffic_all(load(fixtures_dir, "ex", "traffic_all"))
    assert rows and all(r["slot_ts"].minute % 15 == 0 for r in rows)
    assert all(isinstance(r["volume"], int) for r in rows)


def test_ex_sms(fixtures_dir):
    rows = ex.parse_sms(load(fixtures_dir, "ex", "realtime_sms"))
    assert rows and all(len(r["msg_hash"]) == 64 for r in rows)
    assert len({r["msg_hash"] for r in rows}) == len(rows)


def test_korail(fixtures_dir):
    plan, total = korail.parse_plan(load(fixtures_dir, "korail", "run_plan"))
    assert plan and total > len(plan)
    p = plan[0]
    assert p["plan_arr_at"] > p["plan_dep_at"] and p["plan_dep_at"].tzinfo is not None
    info, _ = korail.parse_info(load(fixtures_dir, "korail", "run_info"))
    assert info[0]["stop_type_cd"] == "01" and info[0]["arr_at"] is None and info[0]["dep_at"] is not None
    assert info[-1]["stop_type_cd"] == "05" and info[-1]["dep_at"] is None
    assert [i["run_seq"] for i in info] == sorted(i["run_seq"] for i in info)


def test_kma(fixtures_dir):
    rows = kma.parse_vilage(load(fixtures_dir, "kma", "vilage_fcst"))
    assert rows and {r["category"] for r in rows} <= kma.KEEP
    assert all(r["fcst_at"] > r["base_at"] for r in rows)


def test_airkorea(fixtures_dir):
    rows = airkorea.parse_sido(load(fixtures_dir, "airkorea", "sido_realtime"))
    assert rows and rows[0]["sido_name"] == "대전"
    assert all(r["pm25"] is None or r["pm25"] >= 0 for r in rows)  # '-' → None
    assert airkorea.parse_sido({"response": {"body": {"items": [
        {"dataTime": "2026-09-24 24:00", "stationName": "x", "sidoName": "대전"}]}}})[0]["data_time"] == \
        dt.datetime(2026, 9, 25, tzinfo=KST)
    # dataTime 이 null 인 행은 건너뛴다 (실측 회귀)
    assert airkorea.parse_sido({"response": {"body": {"items": [
        {"dataTime": None, "stationName": "x", "sidoName": "대전"}]}}}) == []


def test_kakao(fixtures_dir):
    r = kakao.parse_future(load(fixtures_dir, "kakao", "future_directions"))
    assert r and r["duration_sec"] > 3600 and r["distance_m"] > 100_000
    lat, lon = kakao.parse_station(load(fixtures_dir, "kakao", "keyword"))
    assert 36.3 < lat < 36.4 and 127.4 < lon < 127.5  # 대전역
