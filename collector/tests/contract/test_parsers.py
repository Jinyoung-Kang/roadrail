"""계약 테스트: W1 스모크에서 저장한 실제 응답(fixtures/<provider>/*.json · UTIC 는 XML)의 필드 매핑 (10장).
응답 형식이 바뀌면 `python3 tools/smoke.py --save` 로 갱신하고 이 테스트가 무엇이 깨졌는지 알려 준다."""
import datetime as dt
import hashlib
import json
import xml.etree.ElementTree as ET

from roadrail.core.timeutil import KST
from roadrail.providers import airkorea, ex, kakao, kasi, kma, korail, utic


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


def test_ex_units_out_of_range_coordinates_become_unknown():
    # 리뷰: V17 경위도 CHECK 뒤로 범위 밖 좌표 한 건이 톨게이트 동기화 전체를 CheckViolation 으로 실패시킬 수 있었다
    # → 문자 안내 · UTIC 처럼 대한민국 범위 밖은 좌표 없음(짐작하지 않음)
    body = {"list": [{"unitCode": "101 ", "unitName": "서울", "yValue": "127.1", "xValue": "37.5"},
                     {"unitCode": "102", "unitName": "부산", "yValue": "0", "xValue": "0"},
                     {"unitCode": "103", "unitName": "대전", "yValue": "36.35", "xValue": "127.38"}]}
    units = {u["unit_code"]: (u["lat"], u["lon"]) for u in ex.parse_units(body)}
    assert units == {"101": (None, None), "102": (None, None), "103": (36.35, 127.38)}


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


def test_sms_location_uses_latitude_and_altitude_as_longitude():
    # 실측(2026-09-25): '남경주부근(68K)-남경주부근(71K)' → latitude 35.724019 · altitude 129.296973 (경주 남쪽)
    from roadrail.providers import ex
    base = {"accDate": "2026.09.25", "accHour": "04:12:40", "roadNM": "동해선", "smsText": "(1차로) 노면보강 공사",
            "accTypeCode": "02", "accType": "작업", "accProcessNM": "진행"}
    got = ex.parse_sms({"realTimeSMSList": [
        {**base, "latitude": 35.724019, "altitude": 129.296973, "accPointNM": "남경주부근(68K)-남경주부근(71K)"},
        {**base, "smsText": "b", "latitude": None, "altitude": None, "accPointNM": "/"},
        {**base, "smsText": "c", "latitude": 0, "altitude": 0, "accPointNM": " "},
    ]})
    assert (got[0]["lat"], got[0]["lon"], got[0]["point_name"]) == (35.724019, 129.296973, "남경주부근(68K)-남경주부근(71K)")
    assert (got[1]["lat"], got[1]["point_name"]) == (None, None)   # 좌표 없음 → 위치를 추정하지 않음
    assert got[2]["lat"] is None                                     # 국내 범위 밖 → 버림


def test_kasi_rest_days(fixtures_dir):
    # 한국천문연구원 특일 정보 2026년 (실제 응답): 공휴일 · 대체공휴일 · 선거일 22일
    rows = kasi.parse_rest_days(load(fixtures_dir, "kasi", "rest_days_2026"))
    days = {r["day"]: r["name"] for r in rows}
    assert len(rows) == 22
    assert days[dt.date(2026, 9, 24)] == "추석" and days[dt.date(2026, 10, 5)] == "대체공휴일(개천절)"
    # 한 건이면 item 이 객체, 0 건이면 items 가 빈 문자열
    one = {"response": {"body": {"items": {"item": {"locdate": 20261225, "dateName": "기독탄신일", "isHoliday": "Y"}}}}}
    assert [r["day"] for r in kasi.parse_rest_days(one)] == [dt.date(2026, 12, 25)]
    assert kasi.parse_rest_days({"response": {"body": {"items": ""}}}) == []


def test_utic_incidents(fixtures_dir):
    # 실제 응답의 사고 · 공사 · 통제 3건 + 경계 사례 4건 (fixtures/utic/ims.xml)
    items = utic.parse_incidents(ET.parse(fixtures_dir / "utic" / "ims.xml").getroot())
    assert len(items) == 5                       # 식별자 없음 · 시각 형식이 깨진 행은 버린다
    acc, work, ctrl, outside, unknown = items
    assert (acc["type_code"], acc["type_name"], acc["route_name"]) == ("U1", "사고", "올림픽대로")
    assert acc["sent_at"] == dt.datetime(2026, 9, 28, 17, 30, tzinfo=KST)
    assert acc["end_at"] == dt.datetime(2026, 9, 28, 18, 0, tzinfo=KST)
    assert 37.5 < acc["lat"] < 37.6 and 126.8 < acc["lon"] < 126.9          # locationDataX = 경도
    assert acc["content"].startswith("[사고]") and acc["lane"] == "차로"
    assert acc["msg_hash"] == hashlib.sha256(b"UTIC:L90173916274").hexdigest()
    assert (work["type_name"], ctrl["type_name"]) == ("공사", "통제")
    assert outside["lat"] is None and outside["lon"] is None                 # 대한민국 밖 좌표는 위치 없음으로
    assert (unknown["type_code"], unknown["type_name"]) == ("U9", "행사")    # 모르는 코드는 제목 머리를 쓴다
    assert unknown["route_name"] == "올림픽대로"                               # 도로명 앞뒤 기호('*…,')는 걷어 낸다


def test_utic_time():
    assert utic.parse_time("2026년 09월 28일  17시 30분") == dt.datetime(2026, 9, 28, 17, 30, tzinfo=KST)
    assert utic.parse_time("2026년 02월 30일 10시 00분") is None             # 없는 날짜
    assert utic.parse_time("") is None and utic.parse_time(None) is None
