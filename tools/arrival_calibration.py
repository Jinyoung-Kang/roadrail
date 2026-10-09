#!/usr/bin/env python3
"""도착 확률 보정 검사 (AR-1 · ADR-029) — DB 는 읽기만. make arrival-calibration

화면의 확률은 '최근 30일 같은 열차 · 같은 역 쌍의 도착 지연 ≤ 여유'인 운행의 비율(경험적 누적분포)이다.
이 규칙이 실제로 맞는지, 날짜 d 의 운행을 [d-30, d-1] 기록만으로 예측해 본다(미래 정보 없음).

  A. 전국 종착역 도착 (rail.train_punctuality, 계획 시각을 확인한 운행) — 표본이 크다
  B. 주요 역 쌍 중간역 도착 (rail.od_trips_real) — 화면이 실제로 쓰는 경로

  1) 신뢰도 표: 여유 m ∈ {0, 2, 5, 10, 15, 20, 30}분마다 예측 p = 기록 중 지연 ≤ m 의 비율, 실제 = 그날 지연 ≤ m.
     p 구간별 평균 예측 · 실제 적중률. Brier 점수(낮을수록 좋음).
  2) 판단 검사: 신뢰 수준 c(80 · 90 · 95%)를 만족한다고 말한 경우(p ≥ c, 기록 15회 이상) 실제 적중률이 c - 5%p 이상인가.
     '모두 기한 안'(p = 1)은 따로 센다 — 화면은 이것을 100% 라고 하지 않는다.

  C. 자동차(3단계, 결정 D1-A): 카카오 예측 ↔ 궤적 합 정답(ana.kakao_eta_eval)의 비율 R = 실제 ÷ 예측.
     시간대(0–6 · 6–10 · 10–16 · 16–20 · 20–24시) × 공휴일 칸마다 앞 기간의 R 분위수 q_c 로 '예측 × q_c 안에 도착'을 말하고,
     마지막 14일에서 실제 적중률을 잰다. 정답 기간이 28일 미만이거나 칸 표본이 50 미만이면 '보류'(화면에 자동차 확률을 내지 않음).

한계: 환승 여정(구간 확률의 곱 · 독립 가정)은 여기서 재지 않는다. 운행 취소는 기록에 없다.
표준 라이브러리만 쓴다. 표는 표준 출력(마크다운)으로.
"""
import argparse
import bisect
import subprocess
import sys
from collections import defaultdict
from datetime import date, timedelta

MARGINS = [0, 2, 5, 10, 15, 20, 30]
CONFIDENCES = [0.8, 0.9, 0.95]
MIN_SAMPLES = 15          # ArrivalOdds.MIN_SAMPLES_FOR_PERCENT 와 같다
WINDOW = 30               # 화면과 같은 30일
TOLERANCE = 0.05          # 출시 조건 ±5%p
PAIRS = [("3900023", "3900073", "서울→대전"), ("3900073", "3900023", "대전→서울"), ("3900023", "3900114", "서울→부산"),
         ("3900023", "3900096", "서울→동대구"), ("3900025", "3900229", "용산→광주송정"), ("3900023", "3900587", "서울→강릉")]
BINS = [(0.0, 0.5), (0.5, 0.8), (0.8, 0.9), (0.9, 0.95), (0.95, 1.0)]


def psql(sql: str) -> list[list[str]]:
    out = subprocess.run(["docker", "compose", "exec", "-T", "db", "psql", "-U", "roadrail", "-d", "roadrail", "-X", "-At", "-F", "\t",
                          "-v", "ON_ERROR_STOP=1", "-c", "SET default_transaction_read_only = on; " + sql],
                         check=True, capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines() if line and not line.startswith("SET")]


def load_terminal(start: date, end: date):
    rows = psql(f"""SELECT run_ymd, trn_no || ':' || dep_stn_cd || '>' || arr_stn_cd, arr_delay_min FROM rail.train_punctuality
                    WHERE status = 'OK' AND arr_delay_min IS NOT NULL AND run_ymd BETWEEN '{start}' AND '{end}'""")
    return [(date.fromisoformat(d), k, float(v)) for d, k, v in rows]


def load_pairs(start: date, end: date):
    out = []
    for dep, arr, _ in PAIRS:
        rows = psql(f"""SELECT run_ymd, trn_no, arr_delay_min FROM rail.od_trips_real('{dep}', '{arr}', '{start}', '{end}')
                        WHERE arr_delay_min IS NOT NULL""")
        out += [(date.fromisoformat(d), f"{t}:{dep}>{arr}", float(v)) for d, t, v in rows]
    return out


def evaluate(runs):
    """runs: (날짜, 열차·역 쌍 키, 지연) → 신뢰도 표 · 판단 검사"""
    by_key = defaultdict(list)
    for d, k, v in runs:
        by_key[k].append((d, v))
    bins = {b: [0, 0.0, 0] for b in BINS}          # 건수 · 예측 합 · 적중
    ones = [0, 0]                                   # p = 1 (모두 기한 안): 건수 · 적중
    decision = {c: [0, 0] for c in CONFIDENCES}     # 만족이라 말한 건수 · 적중
    brier, nb, runs_used = 0.0, 0, 0
    for k, series in by_key.items():
        series.sort()
        days = [d for d, _ in series]
        for i, (d, actual) in enumerate(series):
            lo = bisect.bisect_left(days, d - timedelta(days=WINDOW))
            hi = bisect.bisect_left(days, d)        # d 전날까지 — 그날 기록은 보지 않는다
            hist = sorted(v for _, v in series[lo:hi])
            if len(hist) < MIN_SAMPLES:
                continue
            runs_used += 1
            for m in MARGINS:
                p = bisect.bisect_right(hist, m) / len(hist)
                hit = actual <= m
                brier += (p - hit) ** 2
                nb += 1
                if p >= 1.0:
                    ones[0] += 1
                    ones[1] += hit
                else:
                    for b in BINS:
                        if b[0] <= p < b[1]:
                            bins[b][0] += 1
                            bins[b][1] += p
                            bins[b][2] += hit
                for c in CONFIDENCES:
                    if p + 1e-9 >= c:
                        decision[c][0] += 1
                        decision[c][1] += hit
    return {"runs": runs_used, "bins": bins, "ones": ones, "decision": decision, "brier": brier / nb if nb else float("nan")}


def report(title: str, r) -> bool:
    print(f"\n### {title}\n\n평가한 운행 {r['runs']:,}회 (그 전 30일 기록 {MIN_SAMPLES}회 이상) · Brier {r['brier']:.4f}\n")
    print("| 예측 p 구간 | 건수 | 평균 예측 | 실제 적중 |\n|---|---:|---:|---:|")
    for b, (n, ps, hit) in r["bins"].items():
        if n:
            print(f"| {b[0]:.2f}–{b[1]:.2f} | {n:,} | {ps / n:.3f} | {hit / n:.3f} |")
    n1, h1 = r["ones"]
    if n1:
        print(f"| 1 (모두 기한 안) | {n1:,} | 1.000 | {h1 / n1:.3f} |")
    print("\n| 신뢰 수준 | '만족' 건수 | 실제 적중 | 기준(c − 5%p) | 판정 |\n|---|---:|---:|---:|---|")
    ok = True
    for c, (n, hit) in r["decision"].items():
        rate = hit / n if n else float("nan")
        passed = n > 0 and rate >= c - TOLERANCE
        ok &= passed
        print(f"| {c:.0%} | {n:,} | {rate:.3f} | {c - TOLERANCE:.2f} | {'통과' if passed else '미달'} |")
    return ok


CAR_BANDS = [(0, 6), (6, 10), (10, 16), (16, 20), (20, 24)]
CAR_MIN_CELL, CAR_HOLDOUT_DAYS, CAR_MIN_SPAN_DAYS = 50, 14, 28


def quantile(sorted_vals: list[float], q: float) -> float:
    return sorted_vals[min(len(sorted_vals) - 1, max(0, int(-(-q * len(sorted_vals) // 1)) - 1))]


def car_report() -> bool:
    rows = psql("""SELECT (e.depart_at AT TIME ZONE 'Asia/Seoul')::date, extract(hour FROM e.depart_at AT TIME ZONE 'Asia/Seoul'),
                          h.day IS NOT NULL OR extract(isodow FROM e.depart_at AT TIME ZONE 'Asia/Seoul') >= 6, e.actual_sec::float / e.kakao_sec
                   FROM ana.kakao_eta_eval e LEFT JOIN ref.holiday h ON h.day = (e.depart_at AT TIME ZONE 'Asia/Seoul')::date""")
    print("\n### C. 자동차 — 카카오 예측 ÷ 궤적 합 (결정 D1-A)\n")
    if not rows:
        print("정답 데이터가 아직 없습니다 (ana.kakao_eta_eval) — 보류.")
        return False
    data = [(date.fromisoformat(d), int(float(h)), w == "t", float(r)) for d, h, w, r in rows]
    days = sorted({d for d, *_ in data})
    ratios = sorted(r for *_, r in data)
    span = (days[-1] - days[0]).days + 1
    print(f"짝 {len(data):,}개 · {days[0]} ~ {days[-1]} ({span}일, 관측일 {len(days)}일) · R 중앙값 {quantile(ratios, 0.5):.3f} · "
          f"p10 {quantile(ratios, 0.1):.3f} · p90 {quantile(ratios, 0.9):.3f}\n")
    cut = days[-1] - timedelta(days=CAR_HOLDOUT_DAYS - 1)
    cell = lambda h, wk: (next(i for i, (a, b) in enumerate(CAR_BANDS) if a <= h < b), wk)  # noqa: E731
    train, test = defaultdict(list), defaultdict(list)
    for d, h, wk, r in data:
        (test if d >= cut else train)[cell(h, wk)].append(r)
    print("| 칸 (시간대 · 주말/공휴일) | 앞 기간 표본 | 마지막 14일 표본 |\n|---|---:|---:|")
    for k in sorted(set(train) | set(test)):
        a, b = CAR_BANDS[k[0]]
        print(f"| {a:02d}–{b:02d}시 · {'휴일' if k[1] else '평일'} | {len(train[k])} | {len(test[k])} |")
    ready = [k for k in train if len(train[k]) >= CAR_MIN_CELL and test.get(k)]
    if span < CAR_MIN_SPAN_DAYS or not ready:
        print(f"\n보류 — 정답 기간 {span}일(필요 {CAR_MIN_SPAN_DAYS}일) · 표본 {CAR_MIN_CELL} 이상인 칸 {len(ready)}개. 자동차 확률은 내지 않습니다.")
        return False
    ok = True
    print("\n| 신뢰 수준 | 평가 짝 | 실제 적중 | 기준(c − 5%p) | 판정 |\n|---|---:|---:|---:|---|")
    for c in CONFIDENCES:
        hits = n = 0
        for k in ready:
            q = quantile(sorted(train[k]), c)
            n += len(test[k])
            hits += sum(r <= q for r in test[k])
        rate = hits / n
        passed = abs(rate - c) <= TOLERANCE
        ok &= passed
        print(f"| {c:.0%} | {n:,} | {rate:.3f} | ±{TOLERANCE:.0%}p | {'통과' if passed else '미달'} |")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--from", dest="start", type=date.fromisoformat, help="평가 시작일 (기본: 기록 첫날 + 30일)")
    ap.add_argument("--to", dest="end", type=date.fromisoformat, help="평가 끝날 (기본: 기록 마지막 날)")
    a = ap.parse_args()
    first, last = (date.fromisoformat(x) for x in psql("SELECT min(run_ymd), max(run_ymd) FROM rail.train_punctuality")[0])
    end = a.end or last
    start = a.start or first + timedelta(days=WINDOW)
    load_from = start - timedelta(days=WINDOW)
    print(f"## 도착 확률 보정 검사 — 평가 {start} ~ {end} (기록 {load_from} 부터, 규칙 AR-v1)")
    ok_a = report("A. 전국 종착역 도착", evaluate([r for r in load_terminal(load_from, end)]))
    ok_b = report("B. 주요 역 쌍 (" + " · ".join(p[2] for p in PAIRS) + ")", evaluate(load_pairs(load_from, end)))
    print(f"\n결론(기차): {'통과' if ok_a and ok_b else '미달'} (A {'통과' if ok_a else '미달'} · B {'통과' if ok_b else '미달'})")
    ok_c = car_report()
    print(f"\n결론(자동차): {'통과 — 화면에 낼 수 있음' if ok_c else '보류 — 화면에 내지 않음'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
