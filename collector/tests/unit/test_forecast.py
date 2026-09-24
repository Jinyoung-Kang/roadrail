"""예측 모델 골든 케이스 (fixtures/forecast_cases.json — Java ForecastModelsTest 와 공유) + 백테스트."""
import datetime as dt
import json
import math

import numpy as np
import pandas as pd
import pytest

from roadrail.analytics.backtest import run_backtest
from roadrail.analytics.baseline import compute_baseline
from roadrail.analytics.forecast import predict_all
from roadrail.core.timeutil import KST


def load_cases(fixtures_dir):
    doc = json.loads((fixtures_dir / "forecast_cases.json").read_text())
    bl = {(e["dow"], e["slot"]): (e["p50"], e["n"]) for e in doc["baseline"]}
    return bl, doc["cases"]


def test_golden_cases(fixtures_dir):
    bl, cases = load_cases(fixtures_dir)
    assert len(cases) >= 6
    for c in cases:
        got = predict_all(bl, dt.datetime.fromisoformat(c["current"]), c["currentSec"],
                          dt.datetime.fromisoformat(c["target"]), c["tauMin"])
        assert got == c["expected"], c["name"]


def synthetic(days: int, start: dt.date, noise: float = 0.0, seed: int = 7) -> pd.DataFrame:
    """규칙적인 러시아워(08시·18시 봉우리)를 가진 5분 시계열."""
    rng = np.random.default_rng(seed)
    rows = []
    for d in range(days):
        day = dt.datetime(start.year, start.month, start.day, tzinfo=KST) + dt.timedelta(days=d)
        for i in range(288):
            t = day + dt.timedelta(minutes=5 * i)
            h = t.hour + t.minute / 60
            v = 5400 + 1800 * math.exp(-((h - 8) ** 2) / 2) + 2400 * math.exp(-((h - 18) ** 2) / 3)
            v *= 1 + noise * rng.standard_normal()
            rows.append(("SEL-DJN", "DN", t, int(v)))
    return pd.DataFrame(rows, columns=["corridor_id", "direction", "slot_ts", "travel_sec"])


def test_baseline_quantiles_and_fallback_rows():
    df = synthetic(14, dt.date(2026, 9, 1))
    bl = compute_baseline(df)
    assert set(bl["dow"]) == {0, 1, 2, 3, 4, 5, 6, 7}
    row = bl[(bl.dow == 0) & (bl.slot_idx == 216)].iloc[0]  # 18:00
    assert row.n == 14 and row.p50_sec == row.p90_sec  # 잡음 없음 → 모든 날 같은 값


def test_backtest_on_regular_rush_hour_prefers_baseline_models():
    df = synthetic(70, dt.date(2026, 7, 1), noise=0.03)
    rows, start, end = run_backtest(df, dt.date(2026, 9, 9))
    by = {(r.model, r.horizon_min): r for r in rows}
    for h in (120, 180, 240):
        # 규칙적 패턴에서는 기준선 계열이 '현재값 유지' 보다 먼 horizon 에서 정확해야 한다
        assert by[("M1", h)].mae_sec < by[("persistence", h)].mae_sec
        assert by[("M0", h)].mae_sec < by[("persistence", h)].mae_sec
    assert all(r.n > 0 for r in rows) and (end - start).days == 28


def test_backtest_is_deterministic():
    df = synthetic(40, dt.date(2026, 8, 1), noise=0.05)
    a, _, _ = run_backtest(df, dt.date(2026, 9, 9), days=7)
    b, _, _ = run_backtest(df.sample(frac=1.0, random_state=3), dt.date(2026, 9, 9), days=7)
    assert [(r.model, r.horizon_min, r.mae_sec) for r in a] == [(r.model, r.horizon_min, r.mae_sec) for r in b]


def test_backtest_empty_series():
    rows, _, _ = run_backtest(pd.DataFrame(columns=["corridor_id", "direction", "slot_ts", "travel_sec"]),
                              dt.date(2026, 9, 9))
    assert rows == []


@pytest.mark.parametrize("h", [0, 90, 180])
def test_m1_between_current_and_baseline(h):
    bl = {(5, 204): (6000, 8), (0, 204 + h // 5): (5000, 10), (5, 204 + h // 5): (5000, 10)}
    cur = dt.datetime(2026, 10, 9, 17, 0, tzinfo=KST)
    p = predict_all(bl, cur, 7000, cur + dt.timedelta(minutes=h))
    assert min(5000, 7000) <= p["M1"] <= 7000 + 0
