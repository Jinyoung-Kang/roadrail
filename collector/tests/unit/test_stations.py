from roadrail.pipeline.stations import pick_station, split_hint


def doc(name, cat, addr="", x="127.0", y="37.0"):
    return {"place_name": name, "category_name": cat, "address_name": addr, "x": x, "y": y}


def test_prefers_train_station_category():
    body = {"documents": [doc("대전역 대전1호선", "교통,수송 > 지하철,전철 > 대전1호선", y="1"),
                          doc("대전역", "교통,수송 > 기차,철도 > 기차역 > KTX정차역", y="2")]}
    assert pick_station(body, "대전") == (2.0, 127.0)


def test_falls_back_to_subway_category_for_commuter_lines():
    body = {"documents": [doc("가평역 경춘선", "교통,수송 > 지하철,전철 > 수도권경춘선", y="3")]}
    assert pick_station(body, "가평") == (3.0, 127.0)


def test_parenthesized_names_and_region_hint():
    assert split_hint("판교(경기)") == ("판교", "경기")
    body = {"documents": [doc("판교역", "교통,수송 > 기차,철도 > 기차역", "충남 서천군 판교면", y="4"),
                          doc("판교역 신분당선", "교통,수송 > 지하철,전철 > 신분당선", "경기 성남시 분당구", y="5")]}
    assert pick_station(body, "판교(충남)") == (4.0, 127.0)
    assert pick_station(body, "판교(경기)") == (5.0, 127.0)
    assert pick_station({"documents": [doc("김천(구미)역", "교통,수송 > 기차,철도 > 기차역 > KTX정차역", y="6")]},
                        "김천구미") == (6.0, 127.0)


def test_station_with_suffix_name():
    body = {"documents": [doc("진부(오대산)역", "교통,수송 > 기차,철도 > 기차역 > KTX정차역", y="7")]}
    assert pick_station(body, "진부") == (7.0, 127.0)


def test_no_match_returns_none():
    assert pick_station({"documents": [doc("대전역 맛집", "음식점")]}, "대전") is None
