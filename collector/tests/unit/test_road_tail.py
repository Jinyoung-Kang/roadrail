"""도로 꼬리 커서 — 저장 형식과 '원천이 날짜를 넘겼는지' 판정 (H1)."""
from roadrail.pipeline.road import TailCursor, parse_cursor, tail_is_stale


def test_cursor_round_trip_and_page():
    c = TailCursor("20261003", 280)
    assert parse_cursor(c.dump()) == c
    assert c.page == 3            # 99행씩 — 280행을 봤으면 3쪽부터
    assert TailCursor("20261003", 98).page == 1


def test_unknown_formats_mean_start_over():
    for raw in (None, "", "280", "2026103:5", "20261003:", "20261003:x", "abcdefgh:5"):
        assert parse_cursor(raw) is None, raw


def test_tail_page_from_another_day_or_beyond_the_end_is_stale():
    cur = TailCursor("20261003", 280)
    assert tail_is_stale(cur, 3, "20261004", 3)      # 원천이 새 날로 넘어감
    assert tail_is_stale(cur, 3, None, 1)            # 쪽이 사라짐(목록이 줄어듦)
    assert not tail_is_stale(cur, 3, "20261003", 3)  # 같은 날 꼬리 — 그대로
    assert not tail_is_stale(cur, 1, "20261004", 1)  # 1쪽은 날짜가 바뀌어도 그 쪽부터 다시 세면 된다
