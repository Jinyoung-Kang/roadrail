import datetime as dt

from roadrail.core.timeutil import KST
from roadrail.pipeline.road import match_incident

ROUTES = {"SEL-DJN": {"경부선"}, "SEL-GNG": {"경부선", "영동선", "동해선"}, "SEL-MKP": {"서해안선"}}
PLACES = {"SEL-DJN": {"서울", "천안", "대전"}, "SEL-GNG": {"서울", "원주", "강릉"}, "SEL-MKP": {"서서울", "목포"}}
MAIN = {"SEL-DJN": "경부선", "SEL-GNG": "영동선", "SEL-MKP": "서해안선"}


def inc(route, text, type_code="01"):
    return dict(route_name=route, content=text, type_code=type_code,
                sent_at=dt.datetime(2026, 9, 24, 17, 0, tzinfo=KST))


def test_main_route_matches_without_place():
    assert match_incident(inc("경부선", "사고 발생 2차로 차단"), ROUTES, PLACES, MAIN) == ["SEL-DJN"]


def test_place_mention_matches_secondary_route_corridor():
    # 경부선은 서울–강릉의 보조 노선: 길 영업소(서울)가 위치로 언급되면 매칭
    assert match_incident(inc("경부선", "서울TG 부근 정체"), ROUTES, PLACES, MAIN) == ["SEL-DJN", "SEL-GNG"]


def test_direction_word_is_not_a_place():
    # '서울방향' 은 진행 방향이지 위치가 아니다
    assert match_incident(inc("경부선", "청주 부근 서울방향 공사"), ROUTES, PLACES, MAIN) == ["SEL-DJN"]


def test_promotion_and_unknown_route_not_matched():
    assert match_incident(inc("경부선", "상황실 문의", type_code="15"), ROUTES, PLACES, MAIN) == []
    assert match_incident(inc("제3경인선", "사고"), ROUTES, PLACES, MAIN) == []
