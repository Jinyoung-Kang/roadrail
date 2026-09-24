#!/usr/bin/env python3
"""API 응답 시간 측정 (표준 라이브러리만) — `make bench` / `python3 tools/bench.py [BASE] [N]`.

각 시나리오를 N 번 부르고 p50 · p95 · 최대를 출력한다. '철도 분석 화면'은 실제 화면처럼 3개 요청을 동시에 보낸다.
캐시가 있는 엔드포인트는 첫 호출(미적중)과 이후(적중)를 나눠 본다.
"""
from __future__ import annotations

import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8300"
N = int(sys.argv[2]) if len(sys.argv) > 2 else 15

SEL, DJN, BSN = "3900023", "3900073", "3900114"
PUNCT = "/api/v1/rail/od/punctuality?dep={a}&arr={b}&from=2026-06-26&to=2026-09-24&groupBy={g}"


def get(path: str) -> tuple[float, int]:
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(BASE + path, timeout=30) as r:
            r.read()
            code = r.status
    except urllib.error.HTTPError as e:
        code = e.code
    return (time.perf_counter() - t0) * 1000, code


def page(paths: list[str]) -> tuple[float, int]:
    """여러 요청을 동시에 — 가장 늦은 응답까지의 시간"""
    t0 = time.perf_counter()
    with ThreadPoolExecutor(len(paths)) as ex:
        codes = [c for _, c in ex.map(get, paths)]
    return (time.perf_counter() - t0) * 1000, max(codes)


def run(name: str, fn, n: int = N) -> dict:
    ms, codes = [], set()
    for _ in range(n):
        t, c = fn()
        ms.append(t)
        codes.add(c)
    ms.sort()
    p95 = ms[min(len(ms) - 1, int(round(0.95 * (len(ms) - 1))))]
    row = dict(name=name, n=n, p50=round(statistics.median(ms), 1), p95=round(p95, 1), max=round(ms[-1], 1),
               codes=sorted(codes))
    print(f"{name:<44} p50 {row['p50']:>8.1f}ms  p95 {row['p95']:>8.1f}ms  max {row['max']:>8.1f}ms  {row['codes']}")
    return row


if __name__ == "__main__":
    rows = [
        run("수집 상태 /ops/collect-status", lambda: get("/api/v1/ops/collect-status")),
        run("철도 분석 화면 90일 서울→부산 (3요청 동시)",
            lambda: page([PUNCT.format(a=SEL, b=BSN, g=g) for g in ("train", "dow", "hour")])),
        run("정시율 30일 서울→대전 groupBy=train",
            lambda: get(PUNCT.replace("2026-06-26", "2026-08-26").format(a=SEL, b=DJN, g="train"))),
        run("날짜별 운행 서울→대전", lambda: get(f"/api/v1/rail/od/trains?dep={SEL}&arr={DJN}")),
        run("역 목록 가나다순", lambda: get("/api/v1/stations?limit=400&sort=name")),
        run("길 통행시간 48시간", lambda: get("/api/v1/corridors/SEL-DJN/road/series?dir=DN&hours=48")),
    ]
    print(json.dumps(rows, ensure_ascii=False))
