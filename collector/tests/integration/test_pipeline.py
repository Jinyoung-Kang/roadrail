"""멱등 · 결측 · 백필 (10장), seed 멱등 (FR-102), 철도 일 배치 계산 (FR-301~303)."""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path

import httpx
import pytest

from roadrail.core import db, rds
from roadrail.core.timeutil import KST, now_kst
from roadrail.pipeline import rail, road
from roadrail.pipeline.seed import apply_seed
from roadrail.providers.base import JobContext
from roadrail.providers.kma import latlon_to_grid
from roadrail.scheduler.quota import QuotaBudget

SEED = """
units:
  - {code: "101", name: "서울", routeNo: "001", lat: 37.365, lon: 127.102}
  - {code: "103", name: "수원신갈", routeNo: "001", lat: 37.28, lon: 127.11}
  - {code: "528", name: "기흥동탄", routeNo: "001", lat: 37.23, lon: 127.10}
stations:
  - {code: "S1", name: "서울", lat: 37.55, lon: 126.97, source: KAKAO}
  - {code: "S2", name: "대전", lat: 36.33, lon: 127.43, source: KAKAO}
corridors:
  - id: TST
    name: 시험
    originCity: 서울
    destCity: 대전
    road:
      DN:
        - {start: "101", end: "103", distanceKm: 12.0}
        - {start: "103", end: "528", distanceKm: 6.0}
      UP:
        - {start: "528", end: "101", distanceKm: 18.0}
    rail:
      DN: {dep: S1, arr: S2}
      UP: {dep: S2, arr: S1}
    env:
      - {role: origin, name: 서울, lat: 37.55, lon: 126.97, sido: 서울}
      - {role: dest, name: 대전, lat: 36.33, lon: 127.43, sido: 대전}
"""


@pytest.fixture
async def seeded(tmp_path: Path):
    p = tmp_path / "seed.yaml"
    p.write_text(SEED)
    await db.execute("TRUNCATE ref.corridor, ref.toll_unit, ref.station CASCADE")
    await db.execute("TRUNCATE ts.road_travel_time, ts.road_corridor_tt, ops.slot_gap, rail.run_plan, "
                     "rail.run_info, rail.train_punctuality, rail.corridor_trip")
    assert await apply_seed(p) > 0
    return p


async def test_seed_is_idempotent(seeded):
    assert await apply_seed(seeded) == 0
    rows = await db.fetch("SELECT nx, ny FROM ref.corridor_env_point WHERE role = 'origin'")
    assert (rows[0]["nx"], rows[0]["ny"]) == latlon_to_grid(37.55, 126.97) == (60, 126)


def fake_ex(today: str, drop: set[tuple[str, str, str]] | None = None, calls: list | None = None):
    """오늘 00:00~00:20 슬롯을 주는 가짜 realUnitTrtm. drop={(start,end,'HH:MM')} 은 응답에서 뺀다."""
    drop = drop or set()

    def handler(request: httpx.Request) -> httpx.Response:
        q = request.url.params
        s, e = q["iStartUnitCode"], q["iEndUnitCode"]
        if calls is not None:
            calls.append((s, e, q["pageNo"]))
        items = []
        for m in range(0, 25, 5):
            hm = f"00:{m:02d}"
            if (s, e, hm) in drop:
                continue
            items.append({"startUnitCode": s + " ", "endUnitCode": e + " ", "stdDate": today, "stdTime": hm,
                          "timeAvg": "10.0", "timeMin": "8.0", "timeMax": "12.0", "efcvTrfl": "50",
                          "tcsCarTypeCode": "1"})
        items.append({"startUnitCode": s, "endUnitCode": e, "stdDate": today, "stdTime": "00:00",
                      "timeAvg": "20", "tcsCarTypeCode": "2"})  # 다음 차종 → 1종 블록 끝
        return httpx.Response(200, json={"count": len(items), "pageNo": 1, "pageSize": 1,
                                         "realUnitTrtmVO": items, "code": "SUCCESS"})
    return handler


def ctx_with(handler) -> JobContext:
    return JobContext(job_name="road_travel_time", http=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
                      budget=QuotaBudget(rds.client(), lambda p: 1000))


async def count(sql: str) -> int:
    return (await db.fetchone(sql))["n"]


async def test_same_slots_twice_keeps_row_count(seeded):
    today = now_kst().strftime("%Y%m%d")
    await road.collect_travel_time(ctx_with(fake_ex(today)), full=True)
    n1 = await count("SELECT count(*) AS n FROM ts.road_travel_time")
    c1 = await count("SELECT count(*) AS n FROM ts.road_corridor_tt")
    await road.collect_travel_time(ctx_with(fake_ex(today)), full=True)
    assert n1 == 15  # 3구간 × 5슬롯
    assert await count("SELECT count(*) AS n FROM ts.road_travel_time") == n1
    assert await count("SELECT count(*) AS n FROM ts.road_corridor_tt") == c1 == 10  # 2방향 × 5슬롯
    r = await db.fetchone("SELECT travel_sec, quality FROM ts.road_corridor_tt WHERE corridor_id='TST' AND direction='DN' LIMIT 1")
    assert r["travel_sec"] == 1200 and r["quality"] == "OK"


async def test_tail_cursor_fetches_only_from_last_seen_page(seeded):
    today = now_kst().strftime("%Y%m%d")
    calls: list = []
    await road.collect_travel_time(ctx_with(fake_ex(today, calls=calls)))
    first = len(calls)
    calls.clear()
    await road.collect_travel_time(ctx_with(fake_ex(today, calls=calls)))
    assert first == len(calls) == 3  # 구간당 1페이지
    tail = await rds.client().get(rds.tail_key("101", "103", today))
    assert int(tail) == 5


async def test_missing_slot_is_recorded_then_backfilled(seeded):
    today = now_kst().strftime("%Y%m%d")
    # DN 두 구간 모두 00:10 누락 → 실측 0/2 → 결측
    drop = {("101", "103", "00:10"), ("103", "528", "00:10")}
    await road.collect_travel_time(ctx_with(fake_ex(today, drop)), full=True)
    gap = await db.fetch("SELECT slot_ts, backfilled_at FROM ops.slot_gap WHERE series_key = 'TST:DN'")
    assert len(gap) == 1 and gap[0]["backfilled_at"] is None
    assert gap[0]["slot_ts"].astimezone(KST).strftime("%H:%M") == "00:10"

    ctx = ctx_with(fake_ex(today))
    ctx.job_name = "road_gap_backfill"
    await road.backfill_gaps(ctx)
    gap = await db.fetch("SELECT backfilled_at FROM ops.slot_gap WHERE series_key = 'TST:DN'")
    assert gap[0]["backfilled_at"] is not None
    assert await count("SELECT count(*) AS n FROM ts.road_corridor_tt WHERE direction='DN'") == 5


async def test_low_coverage_slot_is_gap(seeded):
    today = now_kst().strftime("%Y%m%d")
    # 짧은 구간(6km)만 00:10 누락 → 실측 1/2 = 50% < 60% → 저장하지 않고 결측(LOW_COVERAGE)
    await road.collect_travel_time(ctx_with(fake_ex(today, {("103", "528", "00:10")})), full=True)
    r = await db.fetchone("""SELECT quality, observed_segs FROM ts.road_corridor_tt
                             WHERE direction='DN' AND to_char(slot_ts, 'HH24:MI') = '00:10'""")
    assert r is None
    assert await count("SELECT count(*) AS n FROM ops.slot_gap WHERE reason = 'LOW_COVERAGE'") == 1


async def test_rail_compute_day(seeded):
    d = dt.date(2026, 9, 23)

    def t(h, m):
        return dt.datetime(2026, 9, 23, h, m, tzinfo=KST)
    plan = [dict(run_ymd=d, trn_no="00001", dep_stn_cd="S1", arr_stn_cd="S2", plan_dep_at=t(5, 13), plan_arr_at=t(6, 10)),
            dict(run_ymd=d, trn_no="00003", dep_stn_cd="S1", arr_stn_cd="S2", plan_dep_at=t(6, 0), plan_arr_at=t(7, 0))]
    info = [dict(run_ymd=d, trn_no="00001", run_seq=1, stn_cd="S1", arr_at=None, dep_at=t(5, 14)),
            dict(run_ymd=d, trn_no="00001", run_seq=2, stn_cd="S2", arr_at=t(6, 18), dep_at=None),
            dict(run_ymd=d, trn_no="00003", run_seq=1, stn_cd="S1", arr_at=None, dep_at=t(6, 0))]  # 도착 행 없음
    n = await rail.compute_day(d, plan, info)
    assert n == 2
    rows = {r["trn_no"]: r for r in await db.fetch("SELECT * FROM rail.train_punctuality")}
    assert float(rows["00001"]["arr_delay_min"]) == 8.0 and rows["00001"]["on_time"] is False
    assert rows["00003"]["status"] == "UNVERIFIED" and rows["00003"]["on_time"] is None
    trips = await db.fetch("SELECT * FROM rail.corridor_trip")
    assert len(trips) == 1 and trips[0]["direction"] == "DN" and trips[0]["arr_basis"] == "EXACT"
    # 재실행 멱등
    await rail.compute_day(d, plan, info)
    assert await count("SELECT count(*) AS n FROM rail.corridor_trip") == 1


async def test_api_call_log_masks_key(seeded, monkeypatch):
    from roadrail.core.config import settings
    monkeypatch.setattr(settings(), "ex_api_key", "SECRET123")
    today = now_kst().strftime("%Y%m%d")
    ctx = ctx_with(fake_ex(today))
    await road.collect_travel_time(ctx, full=True)
    assert ctx.api_calls and all("SECRET123" not in json.dumps(c, ensure_ascii=False) for c in ctx.api_calls)
    assert all('"key": "***"' in c[3] for c in ctx.api_calls)
