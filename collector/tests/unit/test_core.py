"""슬롯 정렬 · 격자 변환 · 시각 파싱 (10장 단위 테스트)."""
import datetime as dt

import pytest

from roadrail.core.log import mask_params
from roadrail.core.timeutil import KST, align_to_5min, iso_dow, parse_korail_dt, parse_ymd_hm, slot_idx, slots_between
from roadrail.providers.kma import latest_base, latlon_to_grid


@pytest.mark.parametrize("given,expected", [
    ("2026-10-10T17:04:59", "2026-10-10T17:00:00"),
    ("2026-10-10T17:05:00", "2026-10-10T17:05:00"),
    ("2026-10-10T23:59:59", "2026-10-10T23:55:00"),
    ("2026-10-11T00:00:00", "2026-10-11T00:00:00"),
])
def test_align_to_5min(given, expected):
    t = dt.datetime.fromisoformat(given).replace(tzinfo=KST)
    assert align_to_5min(t) == dt.datetime.fromisoformat(expected).replace(tzinfo=KST)


def test_align_converts_utc_to_kst():
    t = dt.datetime(2026, 10, 10, 8, 3, tzinfo=dt.UTC)  # = 17:03 KST
    assert align_to_5min(t) == dt.datetime(2026, 10, 10, 17, 0, tzinfo=KST)


def test_slot_idx_boundaries():
    assert slot_idx(dt.datetime(2026, 10, 10, 0, 0, tzinfo=KST)) == 0
    assert slot_idx(dt.datetime(2026, 10, 10, 23, 55, tzinfo=KST)) == 287
    assert slot_idx(dt.datetime(2026, 10, 10, 17, 0, tzinfo=KST)) == 204


def test_slots_between_crosses_midnight():
    s = slots_between(dt.datetime(2026, 10, 10, 23, 50, tzinfo=KST), dt.datetime(2026, 10, 11, 0, 5, tzinfo=KST))
    assert [t.strftime("%d %H:%M") for t in s] == ["10 23:50", "10 23:55", "11 00:00", "11 00:05"]
    assert iso_dow(s[1]) == 6 and iso_dow(s[2]) == 7  # 토 → 일


def test_parsers():
    assert parse_ymd_hm("20260924", "17:55") == dt.datetime(2026, 9, 24, 17, 55, tzinfo=KST)
    assert parse_ymd_hm("20260924", "1945") == dt.datetime(2026, 9, 24, 19, 45, tzinfo=KST)
    assert parse_korail_dt("2026-09-23 05:13:00.0") == dt.datetime(2026, 9, 23, 5, 13, tzinfo=KST)
    assert parse_korail_dt(None) is None


@pytest.mark.parametrize("lat,lon,nx,ny", [
    (37.5665, 126.9780, 60, 127),   # 서울시청 — 기상청 격자표 (60, 127)
    (35.1796, 129.0756, 98, 76),    # 부산시청 (98, 76)
    (36.3504, 127.3845, 67, 100),   # 대전시청 (67, 100)
    (33.4996, 126.5312, 53, 38),    # 제주시 (53, 38)
])
def test_latlon_to_grid_matches_kma_table(lat, lon, nx, ny):
    assert latlon_to_grid(lat, lon) == (nx, ny)


@pytest.mark.parametrize("now,base", [
    ("2026-10-10T17:20", "2026-10-10T17:00"),
    ("2026-10-10T17:10", "2026-10-10T14:00"),   # 17시 발표는 +15분 이후
    ("2026-10-10T01:00", "2026-10-09T23:00"),   # 자정 넘김
])
def test_latest_base(now, base):
    t = dt.datetime.fromisoformat(now).replace(tzinfo=KST)
    assert latest_base(t) == dt.datetime.fromisoformat(base).replace(tzinfo=KST)


def test_mask_params_hides_keys():
    m = mask_params({"key": "abc", "serviceKey": "x", "type": "json"})
    assert m == {"key": "***", "serviceKey": "***", "type": "json"}
