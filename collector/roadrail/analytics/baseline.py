"""기준선: 같은 길·방향·요일·슬롯의 최근 8주 p50·p90 (FR-401). pandas."""
from __future__ import annotations

import datetime as dt

import pandas as pd

from ..core.timeutil import KST

WEEKS = 8


def compute_baseline(df: pd.DataFrame, holidays: frozenset = frozenset()) -> pd.DataFrame:
    """df: columns [corridor_id, direction, slot_ts(tz-aware), travel_sec]
    → [corridor_id, direction, dow, slot_idx, p50_sec, p90_sec, n]  (dow 0 = 전체 요일 대체 기준선)
    holidays: 공휴일(KST 날짜)은 입력에서 뺀다 — '평소' 요일 · 시간 기준선이 명절 정체로 오염되지 않게."""
    cols = ["corridor_id", "direction", "dow", "slot_idx", "p50_sec", "p90_sec", "n"]
    if df.empty:
        return pd.DataFrame(columns=cols)
    d = df.copy()
    t = pd.to_datetime(d["slot_ts"], utc=True).dt.tz_convert(KST)
    if holidays:
        keep = ~t.dt.date.isin(holidays)
        d, t = d[keep], t[keep]
        if d.empty:
            return pd.DataFrame(columns=cols)
    d["dow"] = t.dt.dayofweek + 1
    d["slot_idx"] = (t.dt.hour * 60 + t.dt.minute) // 5

    def agg(g: pd.core.groupby.DataFrameGroupBy) -> pd.DataFrame:
        r = g["travel_sec"].agg(p50_sec=lambda s: s.quantile(0.5), p90_sec=lambda s: s.quantile(0.9), n="count")
        return r.reset_index()

    by_dow = agg(d.groupby(["corridor_id", "direction", "dow", "slot_idx"]))
    all_dow = agg(d.groupby(["corridor_id", "direction", "slot_idx"]))
    all_dow["dow"] = 0
    out = pd.concat([by_dow, all_dow[by_dow.columns]], ignore_index=True)
    out["p50_sec"] = out["p50_sec"].round().astype(int)
    out["p90_sec"] = out["p90_sec"].round().astype(int)
    out["n"] = out["n"].astype(int)
    return out[cols]


def baseline_lookup(bl: pd.DataFrame, corridor_id: str, direction: str) -> dict[tuple[int, int], tuple[int, int]]:
    sub = bl[(bl["corridor_id"] == corridor_id) & (bl["direction"] == direction)]
    return {(int(r.dow), int(r.slot_idx)): (int(r.p50_sec), int(r.n)) for r in sub.itertuples()}


def window(end_day: dt.date) -> tuple[dt.datetime, dt.datetime]:
    """[end_day − 8주, end_day) KST."""
    end = dt.datetime(end_day.year, end_day.month, end_day.day, tzinfo=KST)
    return end - dt.timedelta(weeks=WEEKS), end
