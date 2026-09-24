#!/usr/bin/env python3
"""코리도 시드 생성기 (FR-102, U-5).

도로공사 `updownIcList` 는 노선별 영업소를 **코드 순**으로 주므로 지리적 순서가 아니다.
이 도구는
  1. 노선의 영업소를 출발→도착 벡터에 투영해 순서를 매기고,
  2. 현재 영업소에서 다음 후보들로 `realUnitTrtm` 오늘 행 수를 조회(각 1회 호출)해
     표본이 충분한 첫 영업소로 한 칸씩 전진하는 방식으로
방향별 구간 체인(seq)을 만든다. 인접 영업소 쌍 중에는 매칭 데이터가 없는 쌍이 있어서
(예: 기흥→오산) 고정 체인을 손으로 쓰면 구멍이 생긴다.

결과는 seed/corridors.yaml 로 저장한다. 표준 라이브러리만 사용 (호스트에서 바로 실행).

    python3 tools/build_seed.py            # 전체 재생성
    python3 tools/build_seed.py SEL-DJN    # 한 코리도만 다시 계산해 병합
"""
from __future__ import annotations

import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EX = "https://data.ex.co.kr/openapi"
KAKAO = "https://dapi.kakao.com/v2/local/search/keyword.json"

# ---- 코리도 정의 (사람이 정하는 부분) -------------------------------------------------
# road: 사용할 노선 번호, 하행(DN) 기준 출발·도착 영업소
# rail: 하행 기준 출발역·도착역 (코레일 역코드는 운행정보 응답에서 확인)
# env : 출발·도착 지점 (역 좌표를 그대로 사용), 에어코리아 시도명
CORRIDORS = [
    dict(id="SEL-DJN", name="서울–대전", origin="서울", dest="대전", routes=["001"], road=("101", "115"),
         rail=("3900023", "3900073"), sido=("서울", "대전")),
    dict(id="SEL-CAN", name="서울–천안", origin="서울", dest="천안", routes=["001"], road=("101", "108"),
         rail=("3900023", "3900884"), sido=("서울", "충남")),
    dict(id="SEL-DGU", name="서울–대구", origin="서울", dest="대구", routes=["001"], road=("101", "129"),
         rail=("3900023", "3900096"), sido=("서울", "대구")),
    dict(id="SEL-BSN", name="서울–부산", origin="서울", dest="부산", routes=["001"], road=("101", "140"),
         rail=("3900023", "3900114"), sido=("서울", "부산")),
    dict(id="DJN-DGU", name="대전–대구", origin="대전", dest="대구", routes=["001"], road=("115", "129"),
         rail=("3900073", "3900096"), sido=("대전", "대구")),
    dict(id="DGU-BSN", name="대구–부산", origin="대구", dest="부산", routes=["001"], road=("129", "140"),
         rail=("3900096", "3900114"), sido=("대구", "부산")),
    dict(id="SEL-MKP", name="서울–목포", origin="서울", dest="목포", routes=["015"], road=("253", "509"),
         rail=("3900025", "3900242"), sido=("서울", "전남")),
    dict(id="SEL-GNG", name="서울–강릉", origin="서울", dest="강릉", routes=["001", "050"], extra=["189", "580"], road=("101", "189"),
         rail=("3900023", "3900587"), sido=("서울", "강원")),
]
STATION_QUERY = {  # 카카오 키워드 검색어 (FR-103)
    "3900023": ("서울", "서울역"), "3900025": ("용산", "용산역"), "3900073": ("대전", "대전역"),
    "3900096": ("동대구", "동대구역"), "3900114": ("부산", "부산역"), "3900884": ("천안아산", "천안아산역"),
    "3900242": ("목포", "목포역"), "3900587": ("강릉", "강릉역"),
}
MIN_ROWS = 120      # 오늘 누적 행 수(전 차종) — 이보다 적으면 표본 부족으로 보고 다음 후보 시도
LOOKAHEAD = 5       # 한 칸 전진할 때 시도할 후보 수
EXCLUDE_WORDS = ("휴게소", "테스트", "(개)", "물류")


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
CALLS = 0


def http_json(url: str, params: dict, headers: dict | None = None) -> dict:
    global CALLS
    q = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{url}?{q}", headers=headers or {})
    for attempt in range(3):
        try:
            CALLS += 1
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception:  # noqa: BLE001 — 도구: 재시도 후 포기
            if attempt == 2:
                raise
            time.sleep(1.5)
    raise RuntimeError("unreachable")


def haversine_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    (la1, lo1), (la2, lo2) = a, b
    p = math.pi / 180
    h = math.sin((la2 - la1) * p / 2) ** 2 + math.cos(la1 * p) * math.cos(la2 * p) * math.sin((lo2 - lo1) * p / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def load_units() -> dict[str, dict]:
    units: dict[str, dict] = {}
    page = 1
    while True:
        d = http_json(f"{EX}/locationinfo/locationinfoUnit",
                      dict(key=ENV["EX_API_KEY"], type="json", numOfRows=99, pageNo=page))
        for u in d.get("list") or []:
            code = u["unitCode"].strip()
            try:
                lat, lon = float(u["yValue"]), float(u["xValue"])
            except (TypeError, ValueError):
                lat = lon = None
            units[code] = dict(code=code, name=u["unitName"], route_no=u["routeNo"], route_name=u["routeName"],
                               lat=lat, lon=lon)
        if page >= (d.get("pageSize") or 0):
            return units
        page += 1


def route_units(route_no: str) -> list[str]:
    d = http_json(f"{EX}/basicinfo/updownIcList", dict(key=ENV["EX_API_KEY"], type="json", routeNo=route_no,
                                                         numOfRows=99, pageNo=1))
    return [u["unitCode"].strip() for u in d.get("upDownIcLists") or []]


def today_rows(start: str, end: str) -> int:
    d = http_json(f"{EX}/trtm/realUnitTrtm", dict(key=ENV["EX_API_KEY"], type="json", iStartUnitCode=start,
                                                   iEndUnitCode=end, numOfRows=1, pageNo=1))
    return int(d.get("count") or 0)


def ordered_candidates(units: dict, codes: set[str], a: str, b: str) -> list[str]:
    """a→b 벡터에 투영한 순서. 경로에서 너무 벗어난(수직거리 > 길이 35%) 영업소는 제외."""
    ua, ub = units[a], units[b]
    ax, ay, bx, by = ua["lon"], ua["lat"], ub["lon"], ub["lat"]
    vx, vy = bx - ax, by - ay
    L2 = vx * vx + vy * vy
    out = []
    for c in codes:
        u = units.get(c)
        if not u or u["lat"] is None or c in (a, b) or any(w in u["name"] for w in EXCLUDE_WORDS):
            continue
        t = ((u["lon"] - ax) * vx + (u["lat"] - ay) * vy) / L2
        perp = abs((u["lon"] - ax) * vy - (u["lat"] - ay) * vx) / math.sqrt(L2)
        if 0.0 < t < 1.0 and perp < 0.35 * math.sqrt(L2):
            out.append((t, c))
    return [c for _, c in sorted(out)] + [b]


def build_chain(units: dict, codes: set[str], a: str, b: str, cache: dict) -> list[dict]:
    cands = ordered_candidates(units, codes, a, b)
    chain: list[dict] = []
    cur = a
    dead: set[tuple[str, str]] = set()   # 막다른 선택 (from, to) — 백트래킹 시 제외
    for _ in range(200):
        if cur == b:
            break
        idx = cands.index(cur) + 1 if cur in cands else 0
        tried = []
        for c in [x for x in cands[idx:] if (cur, x) not in dead][:LOOKAHEAD]:
            key = (cur, c)
            if key not in cache:
                cache[key] = today_rows(cur, c)
            tried.append((cache[key], c))
            if cache[key] >= MIN_ROWS:
                break
        good = [(r, c) for r, c in tried if r > 0]
        if not good and b not in [c for _, c in tried] and (cur, b) not in dead:
            if (cur, b) not in cache:
                cache[(cur, b)] = today_rows(cur, b)
            good = [(cache[(cur, b)], b)] if cache[(cur, b)] > 0 else []
        if not good:
            if not chain:
                raise RuntimeError(f"{a}->{b}: {cur} 에서 이어지는 구간 없음")
            last = chain.pop()           # 한 칸 되돌아가 다른 후보 선택
            dead.add((last["start"], last["end"]))
            print(f"    ↩ {units[cur]['name']}({cur}) 막다름 — 되돌아감")
            cur = last["start"]
            continue
        # 표본 충분(MIN_ROWS) → 최소 기준(MIN_ROWS/3) 순으로 **가까운** 후보 우선, 없으면 행 수 최대
        rows, nxt = next(((r, c) for r, c in good if r >= MIN_ROWS),
                         next(((r, c) for r, c in good if r >= MIN_ROWS // 3), max(good)))
        km = haversine_km((units[cur]["lat"], units[cur]["lon"]), (units[nxt]["lat"], units[nxt]["lon"]))
        chain.append(dict(start=cur, end=nxt, startName=units[cur]["name"], endName=units[nxt]["name"],
                          distanceKm=round(km * 1.15, 1), rowsToday=rows))
        print(f"    {units[cur]['name']}({cur}) → {units[nxt]['name']}({nxt})  rows={rows}  ~{km*1.15:.0f}km")
        cur = nxt
    else:
        raise RuntimeError(f"{a}->{b}: 체인 탐색 한도 초과")
    return chain


def kakao_station(query: str) -> tuple[float, float] | None:
    d = http_json(KAKAO, dict(query=query, size=5),
                  headers={"Authorization": f"KakaoAK {ENV['KAKAO_REST_API_KEY']}"})
    docs = d.get("documents") or []
    rail = [x for x in docs if "기차역" in x.get("category_name", "")] or docs
    if not rail:
        return None
    return float(rail[0]["y"]), float(rail[0]["x"])


def to_yaml(obj, indent=0) -> str:
    """의존성 없이 쓰는 작은 YAML 출력기 (dict/list/스칼라)."""
    pad = "  " * indent
    if isinstance(obj, dict):
        lines = []
        for k, v in obj.items():
            if isinstance(v, (dict, list)) and v:
                lines.append(f"{pad}{k}:")
                lines.append(to_yaml(v, indent + 1))
            else:
                lines.append(f"{pad}{k}: {scalar(v)}")
        return "\n".join(lines)
    if isinstance(obj, list):
        lines = []
        for v in obj:
            if isinstance(v, dict):
                inner = to_yaml(v, indent + 1).splitlines()
                lines.append(f"{pad}- {inner[0].strip()}")
                lines.extend(inner[1:])
            else:
                lines.append(f"{pad}- {scalar(v)}")
        return "\n".join(lines)
    return pad + scalar(obj)


def scalar(v) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, list):
        return "[]"
    return json.dumps(str(v), ensure_ascii=False)


def main(only: list[str]) -> None:
    for k in ("EX_API_KEY", "KAKAO_REST_API_KEY"):
        if not ENV.get(k):
            sys.exit(f"{k} 가 .env 에 없습니다")
    out_path = ROOT / "seed" / "corridors.yaml"
    prev = {}
    if only and (ROOT / "seed" / "corridors.json").exists():
        prev = {c["id"]: c for c in json.loads((ROOT / "seed" / "corridors.json").read_text())["corridors"]}

    print("영업소 좌표 로드…")
    units = load_units()
    route_cache: dict[str, list[str]] = {}
    pair_cache: dict = {}
    stations = {}
    for code, (name, q) in STATION_QUERY.items():
        ll = kakao_station(q)
        stations[code] = dict(code=code, name=name, lat=ll[0] if ll else None, lon=ll[1] if ll else None,
                              source="KAKAO" if ll else None)

    result = []
    for c in CORRIDORS:
        if only and c["id"] not in only:
            if c["id"] in prev:
                result.append(prev[c["id"]])
            continue
        print(f"[{c['id']}] {c['name']}")
        codes: set[str] = set()
        for r in c["routes"]:
            route_cache.setdefault(r, route_units(r))
            codes |= set(route_cache[r])
        codes |= set(c.get("extra", []))  # 다른 노선의 종점 부근 영업소만 추가 (노선 전체를 넣으면 체인이 샌다)
        a, b = c["road"]
        print("  DN")
        dn = build_chain(units, codes, a, b, pair_cache)
        print("  UP")
        up = build_chain(units, codes, b, a, pair_cache)
        o, d = c["rail"]
        so, sd = stations[o], stations[d]
        result.append(dict(
            id=c["id"], name=c["name"], originCity=c["origin"], destCity=c["dest"],
            road=dict(DN=dn, UP=up),
            rail=dict(DN=dict(dep=o, arr=d), UP=dict(dep=d, arr=o)),
            env=[
                dict(role="origin", name=so["name"], lat=so["lat"], lon=so["lon"], sido=c["sido"][0]),
                dict(role="dest", name=sd["name"], lat=sd["lat"], lon=sd["lon"], sido=c["sido"][1]),
            ],
        ))

    used_units = sorted({s[k] for c in result for d in ("DN", "UP") for s in c["road"][d] for k in ("start", "end")})
    doc = dict(
        generatedAt=time.strftime("%Y-%m-%dT%H:%M:%S+09:00"),
        generator="tools/build_seed.py",
        note="구간 체인은 realUnitTrtm 오늘 행 수로 표본 충분성을 확인해 자동 선택 (MIN_ROWS=%d)" % MIN_ROWS,
        stations=list(stations.values()),
        units=[dict(code=u, name=units[u]["name"], routeNo=units[u]["route_no"], lat=units[u]["lat"],
                    lon=units[u]["lon"]) for u in used_units],
        corridors=result,
    )
    (ROOT / "seed" / "corridors.json").write_text(json.dumps(doc, ensure_ascii=False, indent=1))
    header = ("# 코리도 시드 (FR-102) — tools/build_seed.py 가 생성. 손으로 고쳐도 되며 적용은 멱등입니다.\n"
              "# road.<DIR>[]: 방향별 영업소 구간 체인, rail: 역 쌍, env: 날씨·대기 지점\n")
    out_path.write_text(header + to_yaml(doc) + "\n")
    print(f"완료: {out_path.relative_to(ROOT)}  (API 호출 {CALLS}건)")


if __name__ == "__main__":
    main(sys.argv[1:])
