#!/usr/bin/env python3
"""외부 API 스모크 테스트 + 계약 테스트 fixture 캡처 (W1).

    python3 tools/smoke.py          # 공급자 5종 · 8개 오퍼레이션 호출 결과만 출력
    python3 tools/smoke.py --save   # 응답을 fixtures/<provider>/ 에 저장 (행 수를 줄이고 키는 마스킹)

표준 라이브러리만 사용. 키는 .env 에서 읽고, 출력·파일 어디에도 평문으로 남기지 않는다.
"""
from __future__ import annotations

import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KST = dt.timezone(dt.timedelta(hours=9))


def load_env() -> dict[str, str]:
    env = dict(os.environ)
    p = ROOT / ".env"
    if p.exists():
        for line in p.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                env.setdefault(k.strip(), v.strip())
    return env


ENV = load_env()
SECRETS = [ENV.get(k, "") for k in ("EX_API_KEY", "DATA_GO_KR_KEY", "KAKAO_REST_API_KEY") if ENV.get(k)]


def mask(text: str) -> str:
    for s in SECRETS:
        text = text.replace(s, "***").replace(urllib.parse.quote(s, safe=""), "***")
    return text


def call(url: str, params: dict, headers: dict | None = None) -> tuple[int, int, str]:
    req = urllib.request.Request(f"{url}?{urllib.parse.urlencode(params)}", headers=headers or {})
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, int((time.perf_counter() - t) * 1000), r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, int((time.perf_counter() - t) * 1000), e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, int((time.perf_counter() - t) * 1000), repr(e)


def trim(obj, limit: int):
    """리스트를 limit 개로 줄여 fixture 크기를 작게 유지."""
    if isinstance(obj, list):
        return [trim(x, limit) for x in obj[:limit]]
    if isinstance(obj, dict):
        return {k: trim(v, limit) for k, v in obj.items()}
    return obj


def main() -> int:
    save = "--save" in sys.argv
    now = dt.datetime.now(KST)
    yday = (now - dt.timedelta(days=1)).strftime("%Y%m%d")
    # 단기예보 base_time: 02,05,08,11,14,17,20,23시 발표 (+10분 이후 제공) — 직전 발표
    b = now - dt.timedelta(minutes=15)
    hours = [h for h in (2, 5, 8, 11, 14, 17, 20, 23) if h <= b.hour]
    if hours:
        base_date, base_time = b.strftime("%Y%m%d"), f"{hours[-1]:02d}00"
    else:
        base_date, base_time = (b - dt.timedelta(days=1)).strftime("%Y%m%d"), "2300"

    ex, gk, kk = ENV.get("EX_API_KEY", ""), ENV.get("DATA_GO_KR_KEY", ""), ENV.get("KAKAO_REST_API_KEY", "")
    kakao_h = {"Authorization": f"KakaoAK {kk}"}
    checks = [
        ("ex", "unit_location", "https://data.ex.co.kr/openapi/locationinfo/locationinfoUnit",
         dict(key=ex, type="json", numOfRows=5, pageNo=1), None, 5),
        ("ex", "travel_time", "https://data.ex.co.kr/openapi/trtm/realUnitTrtm",
         dict(key=ex, type="json", iStartUnitCode="101", iEndUnitCode="103", numOfRows=99, pageNo=1), None, 99),
        ("ex", "traffic_all", "https://data.ex.co.kr/openapi/trafficapi/trafficAll",
         dict(key=ex, type="json", tmType="2"), None, 20),
        ("ex", "realtime_sms", "https://data.ex.co.kr/openapi/burstInfo/realTimeSms",
         dict(key=ex, type="json", numOfRows=20, pageNo=1), None, 20),
        ("korail", "run_plan", "https://apis.data.go.kr/B551457/run/v2/travelerTrainRunPlan2",
         {"serviceKey": gk, "pageNo": 1, "numOfRows": 5, "returnType": "json", "cond[run_ymd::EQ]": yday}, None, 5),
        ("korail", "run_info", "https://apis.data.go.kr/B551457/run/v2/travelerTrainRunInfo2",
         {"serviceKey": gk, "pageNo": 1, "numOfRows": 30, "returnType": "json", "cond[run_ymd::EQ]": yday,
          "cond[trn_no::EQ]": "00001"}, None, 30),
        ("kma", "vilage_fcst", "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst",
         dict(serviceKey=gk, pageNo=1, numOfRows=60, dataType="JSON", base_date=base_date, base_time=base_time,
              nx=60, ny=127), None, 60),
        ("airkorea", "sido_realtime", "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty",
         dict(serviceKey=gk, pageNo=1, numOfRows=5, returnType="json", sidoName="대전", ver="1.0"), None, 5),
        ("kakao", "keyword", "https://dapi.kakao.com/v2/local/search/keyword.json",
         dict(query="대전역", size=2), kakao_h, 2),
        ("kakao", "future_directions", "https://apis-navi.kakaomobility.com/v1/future/directions",
         dict(origin="127.102077,37.365046", destination="127.448327,36.361324",
              departure_time=(now + dt.timedelta(hours=1)).strftime("%Y%m%d%H%M"), summary="true"), kakao_h, 5),
    ]
    failed = 0
    for provider, name, url, params, headers, limit in checks:
        status, ms, body = call(url, params, headers)
        body = mask(body)
        ok = status == 200 and body.lstrip().startswith("{")
        try:
            parsed = json.loads(body)
        except ValueError:
            parsed, ok = None, False
        failed += 0 if ok else 1
        print(f"{'OK ' if ok else 'ERR'} {provider:9s} {name:18s} HTTP {status} {ms:5d}ms  {len(body):7d}B"
              + ("" if ok else f"  {body[:160]!r}"))
        if save and parsed is not None:
            out = ROOT / "fixtures" / provider / f"{name}.json"
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(trim(parsed, limit), ensure_ascii=False, indent=1) + "\n")
    print(f"\n{len(checks) - failed}/{len(checks)} 성공" + ("  (fixtures 저장)" if save else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
