"""API 입력 매트릭스 (QA) — 경로마다 정상 요청 하나를 기준으로 매개변수 하나씩 정상 · 경계 · 비정상 값으로 바꿔 부른다.

    python3 qa/api_matrix.py [BASE] [OUT]     기본 BASE=http://127.0.0.1:8301 (QA 스택 — 외부 API 키 없음)

판정(자동):
  - 5xx 는 결함 후보 · 응답이 JSON 이 아님 · 스택 트레이스/예외 이름 노출 · 4xx 인데 오류 규약({code, message, traceId}) 아님
  - 분명히 잘못된 입력(NaN · 범위 밖 · 형식 오류)을 2xx 로 받아들임 → 검토 대상
  - 3초 넘는 응답
결과는 JSON 줄(OUT)과 요약(표준 출력). 표준 라이브러리만 쓴다.
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8301"
OUT = sys.argv[2] if len(sys.argv) > 2 else "/tmp/qa_api_matrix.jsonl"
TOKEN = "qa-admin-SECRET123"

BIG = ["2147483647", "2147483648", "9999999999999999999999", "-2147483649"]
NUM_BAD = ["-1", "abc", "", " ", "1.5", "0x10", "1e3", "NaN", "Infinity"] + BIG
COORD = ["90", "90.0001", "-90.0001", "180.0001", "-180.0001", "NaN", "Infinity", "-Infinity", "1e309", "abc", "", "0"]
TEXT = ["", " ", "a" * 61, "a" * 10000, "' OR 1=1--", "%", "_", "%%%%", "\u0000", "<script>alert(1)</script>",
        "../../etc/passwd", "😀🚆", "a\r\nX-Injected: 1", "${jndi:ldap://x/a}", "'; SELECT pg_sleep(3)--", "서울역"]
DATES = ["2026-02-30", "2026-13-01", "20261001", "", "9999-12-31", "0001-01-01", "2026-10-01T00:00", "' OR 1=1--"]
DATETIMES = ["2026-10-01T00:00:00+09:00", "2026-10-01", "2026-13-01T00:00:00+09:00", "abc", "", "9999-12-31T23:59:59Z",
             "0001-01-01T00:00:00Z", "2026-10-01T00:00:00"]
IDS = ["SEL-DJN", "sel-djn", "NOPE", "../health", "SEL-DJN%00", "A" * 300, "' OR 1=1--", "%2e%2e%2f", ""]
STN = ["3900023", "3900073", "0", "39000231", "abc", "", "' OR 1=1--", "3900023%"]

# (이름, 방식, 경로(템플릿), 기본 매개변수, {매개변수: 바꿔 볼 값}, 분명히 잘못된 값 집합(2xx 면 검토))
seoul = dict(fromLat="37.5547", fromLon="126.9706", fromName="서울역", toLat="36.3324", toLon="127.4342", toName="대전역")
ENDPOINTS = [
    ("health", "GET", "/api/v1/health", {}, {}, {}),
    ("corridors", "GET", "/api/v1/corridors", {}, {}, {}),
    ("now", "GET", "/api/v1/corridors/{id}/now", dict(id="SEL-DJN", dir="DN", departIn="0", accessMin="20", carAccessMin="0"),
     dict(id=IDS, dir=["DN", "UP", "dn", "XX", "", "' OR 1=1--"], departIn=["0", "360", "361"] + NUM_BAD,
          accessMin=["0", "180", "181"] + NUM_BAD, carAccessMin=["0", "180", "181"] + NUM_BAD),
     {"dir": {"XX", "", "' OR 1=1--", "dn"}, "departIn": {"361", "-1", "abc", "1.5", "NaN"} | set(BIG)}),
    ("env", "GET", "/api/v1/corridors/{id}/env", dict(id="SEL-DJN", hours="12"),
     dict(id=IDS, hours=["1", "72", "73", "0"] + NUM_BAD), {"hours": {"73", "0", "-1"}}),
    ("corridor_trains", "GET", "/api/v1/corridors/{id}/rail/trains", dict(id="SEL-DJN", dir="DN", date="2026-10-01"),
     dict(id=IDS, dir=["DN", "UP", "XX", "", "' OR 1=1--"], date=DATES), {"dir": {"XX", "' OR 1=1--"}}),
    ("baseline", "GET", "/api/v1/corridors/{id}/road/baseline", dict(id="SEL-DJN", dir="DN"),
     dict(id=IDS, dir=["UP", "XX", "", "' OR 1=1--"]), {"dir": {"XX", "' OR 1=1--"}}),
    ("forecast", "GET", "/api/v1/corridors/{id}/road/forecast", dict(id="SEL-DJN", dir="DN", horizons="60,120"),
     dict(id=IDS, dir=["UP", "XX", ""], horizons=["0", "-60", "100000", "60,abc", ",,,", "", "1," * 2000 + "1", "2147483648"]),
     {"dir": {"XX"}, "horizons": {"-60", "100000", "2147483648"}}),
    ("series", "GET", "/api/v1/corridors/{id}/road/series",
     dict(id="SEL-DJN", dir="DN", **{"from": "2026-10-03T00:00:00+09:00", "to": "2026-10-04T00:00:00+09:00"}, agg="5m"),
     dict(id=IDS, dir=["UP", "XX", ""], agg=["1h", "5m", "1d", "", "' OR 1=1--"], **{"from": DATETIMES, "to": DATETIMES}),
     {"dir": {"XX"}, "agg": {"1d", "' OR 1=1--"}}),
    ("incidents", "GET", "/api/v1/incidents", dict(corridorId="SEL-DJN", since="2026-10-01T00:00:00+09:00", limit="50"),
     dict(corridorId=IDS, since=DATETIMES, limit=["1", "200", "201", "0"] + NUM_BAD), {"limit": {"201", "0", "-1"}}),
    ("collect_status", "GET", "/api/v1/ops/collect-status", {}, {}, {}),
    ("places", "GET", "/api/v1/places/search", dict(q="대전"), dict(q=TEXT), {}),
    ("stations", "GET", "/api/v1/stations", dict(q="서울", limit="20", sort="trains"),
     dict(q=TEXT, limit=["1", "400", "401", "0"] + NUM_BAD, sort=["name", "trains", "", "x", "' OR 1=1--"]),
     {"limit": {"401", "0", "-1"}, "sort": {"x", "' OR 1=1--"}}),
    ("od_punctuality", "GET", "/api/v1/rail/od/punctuality",
     dict(dep="3900023", arr="3900073", **{"from": "2026-09-01", "to": "2026-09-30"}, groupBy="train", thresholdMin="5"),
     dict(dep=STN, arr=STN, groupBy=["dow", "hour", "x", "", "' OR 1=1--"], thresholdMin=["0", "60", "61"] + NUM_BAD,
          **{"from": DATES + ["2026-10-01", "2020-01-01"], "to": DATES + ["2026-08-01", "2030-01-01"]}),
     {"groupBy": {"x", "' OR 1=1--"}, "thresholdMin": {"-1"} | set(BIG)}),
    ("od_trains", "GET", "/api/v1/rail/od/trains", dict(dep="3900023", arr="3900073", date="2026-10-01"),
     dict(dep=STN, arr=STN, date=DATES), {}),
    ("rail_punctuality", "GET", "/api/v1/rail/punctuality",
     dict(corridorId="SEL-DJN", **{"from": "2026-09-01", "to": "2026-09-30"}, dir="DN", groupBy="dow", thresholdMin="5"),
     dict(corridorId=IDS, dir=["UP", "XX", "' OR 1=1--"], groupBy=["train", "hour", "x", ""], thresholdMin=["-1", "61"] + BIG,
          **{"from": DATES, "to": DATES + ["2026-08-01"]}),
     {"dir": {"XX", "' OR 1=1--"}, "groupBy": {"x"}, "thresholdMin": {"-1"} | set(BIG)}),
    ("road_route", "GET", "/api/v1/road/route", dict(seoul, departIn="0"),
     dict(fromLat=COORD, fromLon=COORD, toLat=COORD, fromName=TEXT, departIn=["360", "361"] + NUM_BAD),
     {"fromLat": {"90.0001", "-90.0001", "NaN", "Infinity", "-Infinity", "1e309"}, "departIn": {"361", "-1"}}),
    ("trip", "GET", "/api/v1/trip", dict(seoul, departIn="0"),
     dict(fromLat=COORD, fromLon=COORD, toLon=COORD, fromName=TEXT, toName=TEXT, fromStation=STN,
          departIn=["360", "361"] + NUM_BAD, accessMin=["0", "180", "181"] + NUM_BAD),
     {"fromLat": {"90.0001", "-90.0001", "NaN", "Infinity", "-Infinity", "1e309"},
      "fromLon": {"180.0001", "-180.0001", "NaN", "Infinity", "-Infinity", "1e309"}, "departIn": {"361", "-1"},
      "accessMin": {"181", "-1"}}),
]


def call(method: str, path: str, params: dict, body: bytes | None = None, headers: dict | None = None):
    q = {k: v for k, v in params.items() if k != "id"}
    p = path.replace("{id}", urllib.parse.quote(params.get("id", ""), safe=""))
    url = BASE + p + ("?" + urllib.parse.urlencode(q) if q else "")
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            status, ctype, data = r.status, r.headers.get("Content-Type", ""), r.read()
    except urllib.error.HTTPError as e:
        status, ctype, data = e.code, e.headers.get("Content-Type", ""), e.read()
    except Exception as e:  # noqa: BLE001 — 연결 실패 · 시간 초과도 결과로 남긴다
        return dict(url=url, status=0, ms=(time.perf_counter() - t0) * 1000, error=f"{type(e).__name__}: {e}")
    ms = (time.perf_counter() - t0) * 1000
    text = data.decode("utf-8", "replace")
    flags = []
    if status >= 500:
        flags.append("5xx")
    if "json" not in ctype:
        flags.append("not-json")
    if any(s in text for s in ("Exception", "\tat ", "at com.", "at org.", "stackTrace", "SQLState", "PSQLException")):
        flags.append("trace-leak")
    if 400 <= status < 500:
        try:
            j = json.loads(text)
            if not (isinstance(j, dict) and {"code", "message", "traceId"} <= set(j)):
                flags.append("bad-error-shape")
        except ValueError:
            pass
    if ms > 3000:
        flags.append("slow")
    return dict(url=url, status=status, ms=round(ms, 1), bytes=len(data), flags=flags, body=text[:300])


def main() -> None:
    rows = []
    for name, method, path, base, variants, invalid in ENDPOINTS:
        r = call(method, path, base)
        r.update(endpoint=name, param="(기본)", value="")
        rows.append(r)
        for param, values in variants.items():
            for v in values:
                r = call(method, path, {**base, param: v})
                r.update(endpoint=name, param=param, value=v[:80])
                if v in (invalid or {}).get(param, set()) and 200 <= r["status"] < 300:
                    r["flags"].append("accepted-invalid")
                rows.append(r)
    # 관리 API — 시험용 토큰(QA 스택)
    H = {"X-Admin-Token": TOKEN, "Content-Type": "application/json"}
    bodies = ['{"provider":"KORAIL","from":"2026-09-01","to":"2026-09-02"}', "{", "", "null", "[]",
              '{"provider":"KORAIL","from":"2026-09-02","to":"2026-09-01"}', '{"provider":"EX","from":"2026-09-01","to":"2026-09-02"}',
              '{"provider":"KORAIL","from":"1900-01-01","to":"2026-09-02"}', '{"provider":"KORAIL","from":"2026-09-01","to":"2099-01-01"}',
              '{"provider":"KORAIL","from":"\' OR 1=1--","to":"2026-09-02"}', '{"provider":"KORAIL","from":"2026-09-01","to":"2026-09-02","x":"' + "a" * 100000 + '"}',
              '{"provider":"KORAIL","job":"rail_daily; DROP TABLE x","from":"2026-09-01","to":"2026-09-02"}']
    for b in bodies:
        r = call("POST", "/api/v1/admin/backfill", {}, b.encode(), H)
        r.update(endpoint="admin_backfill", param="body", value=b[:80])
        rows.append(r)
    r = call("POST", "/api/v1/admin/backfill", {}, bodies[0].encode(), {"X-Admin-Token": TOKEN, "Content-Type": "text/plain"})
    r.update(endpoint="admin_backfill", param="content-type", value="text/plain")
    rows.append(r)

    with open(OUT, "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    bad = [r for r in rows if r.get("flags") or r.get("status") == 0]
    print(f"요청 {len(rows)}건 · 상태 {dict(Counter(r['status'] for r in rows))} · 문제 후보 {len(bad)}건")
    print(f"응답 시간 최대 {max(r['ms'] for r in rows):.0f}ms · 가장 큰 응답 {max(r.get('bytes', 0) for r in rows):,}바이트")
    for r in bad:
        print(f"  [{','.join(r.get('flags') or ['conn'])}] {r['endpoint']} {r['param']}={r['value'][:40]!r} → {r['status']} "
              f"{r.get('ms')}ms {r.get('body', r.get('error', ''))[:160]!r}")


if __name__ == "__main__":
    main()
