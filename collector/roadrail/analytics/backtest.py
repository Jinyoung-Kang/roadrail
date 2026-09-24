"""백테스트 (FR-404): 최근 28일, 매 정시 발행 → horizon 분 뒤 실제값과 비교해 MAE·MAPE.

누수 방지: 발행일 D 의 예측은 D 이전 8주 데이터로 만든 기준선만 쓴다 (발행일마다 기준선 재계산).
재현성(NFR-07): 입력 기간 · 모델 버전 · τ 를 결과와 함께 저장. 같은 입력이면 같은 결과 (결정적).
"""
from __future__ import annotations

import datetime as dt
from dataclasses import dataclass

import pandas as pd

from ..core.timeutil import KST
from .baseline import WEEKS, baseline_lookup, compute_baseline
from .forecast import MODEL_VERSION, predict_all

HORIZONS = (60, 120, 180, 240)
DAYS = 28
MODELS = ("M0", "M1", "persistence")


@dataclass
class EvalRow:
    model: str
    corridor_id: str
    direction: str
    horizon_min: int
    mae_sec: float | None
    mape: float | None
    n: int


def run_backtest(series: pd.DataFrame, eval_day: dt.date, tau_min: float = 90.0,
                 days: int = DAYS, horizons=HORIZONS) -> tuple[list[EvalRow], dt.datetime, dt.datetime]:
    """series: [corridor_id, direction, slot_ts, travel_sec] (eval_day 이전 days+8주 포함).
    → (결과 행, 창 시작, 창 끝)"""
    end = dt.datetime(eval_day.year, eval_day.month, eval_day.day, tzinfo=KST)
    start = end - dt.timedelta(days=days)
    rows: list[EvalRow] = []
    if series.empty:
        return rows, start, end
    s = series.copy()
    s["slot_ts"] = pd.to_datetime(s["slot_ts"], utc=True).dt.tz_convert(KST)
    errors: dict[tuple, list[tuple[float, float]]] = {}
    for (cid, direction), g in s.groupby(["corridor_id", "direction"]):
        obs = dict(zip(g["slot_ts"], g["travel_sec"].astype(int), strict=False))
        day = start
        while day < end:
            hist = g[(g["slot_ts"] >= day - dt.timedelta(weeks=WEEKS)) & (g["slot_ts"] < day)]
            bl = baseline_lookup(compute_baseline(hist), cid, direction) if not hist.empty else {}
            for hour in range(24):
                issue = day + dt.timedelta(hours=hour)
                cur = obs.get(pd.Timestamp(issue))
                if cur is None:
                    continue
                for h in horizons:
                    target = issue + dt.timedelta(minutes=h)
                    actual = obs.get(pd.Timestamp(target))
                    if actual is None:
                        continue
                    preds = predict_all(bl, issue, cur, target, tau_min)
                    for m in MODELS:
                        p = preds[m]
                        if p is not None:
                            errors.setdefault((m, cid, direction, h), []).append((abs(p - actual), abs(p - actual) / actual))
            day += dt.timedelta(days=1)
    for (m, cid, direction, h), errs in sorted(errors.items()):
        n = len(errs)
        rows.append(EvalRow(m, cid, direction, h, round(sum(e for e, _ in errs) / n, 1),
                            round(sum(p for _, p in errs) / n, 4), n))
    return rows, start, end


def model_version(tau_min: float) -> str:
    return f"{MODEL_VERSION}(tau={int(tau_min)})"
