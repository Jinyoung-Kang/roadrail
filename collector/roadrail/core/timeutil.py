"""시간대(Asia/Seoul)와 5분 슬롯 유틸."""
from __future__ import annotations

import datetime as dt
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")
SLOT = dt.timedelta(minutes=5)
SLOTS_PER_DAY = 288


def now_kst() -> dt.datetime:
    return dt.datetime.now(KST)


def align_to_5min(ts: dt.datetime) -> dt.datetime:
    """슬롯 시작 시각으로 내림 (17:04:59 → 17:00:00). tz 없는 값은 KST 로 본다."""
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=KST)
    ts = ts.astimezone(KST)
    return ts.replace(minute=ts.minute - ts.minute % 5, second=0, microsecond=0)


def slot_idx(ts: dt.datetime) -> int:
    """하루 안의 슬롯 번호 0..287 (KST)."""
    t = ts.astimezone(KST)
    return (t.hour * 60 + t.minute) // 5


def iso_dow(ts: dt.datetime) -> int:
    """1=월 … 7=일 (KST)."""
    return ts.astimezone(KST).isoweekday()


def parse_ymd_hm(ymd: str, hm: str) -> dt.datetime:
    """('20260924', '17:55') 또는 ('20260924', '1755') → KST datetime."""
    hm = hm.strip().replace(":", "")
    return dt.datetime(int(ymd[:4]), int(ymd[4:6]), int(ymd[6:8]), int(hm[:2]), int(hm[2:4]), tzinfo=KST)


def parse_korail_dt(s: str | None) -> dt.datetime | None:
    """'2026-09-23 05:13:00.0' → KST datetime. 빈 값은 None."""
    if not s:
        return None
    return dt.datetime.strptime(s[:19], "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST)


def day_start(d: dt.date) -> dt.datetime:
    return dt.datetime(d.year, d.month, d.day, tzinfo=KST)


def slots_between(start: dt.datetime, end: dt.datetime) -> list[dt.datetime]:
    """[start, end] 양 끝 포함 5분 슬롯 목록."""
    out, t = [], align_to_5min(start)
    end = align_to_5min(end)
    while t <= end:
        out.append(t)
        t = t + SLOT
    return out
