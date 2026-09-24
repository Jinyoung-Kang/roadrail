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
                     "rail.run_info, rail.train_punctuality")
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


async def test_rail_compute_day_and_od_trips(seeded):
    d = dt.date(2026, 9, 23)

    def t(h, m):
        return dt.datetime(2026, 9, 23, h, m, tzinfo=KST)
    plan = [dict(run_ymd=d, trn_no="00001", dep_stn_cd="S1", arr_stn_cd="S2", plan_dep_at=t(5, 13), plan_arr_at=t(6, 10)),
            dict(run_ymd=d, trn_no="00003", dep_stn_cd="S1", arr_stn_cd="S2", plan_dep_at=t(6, 0), plan_arr_at=t(7, 0))]
    info = [dict(run_ymd=d, trn_no="00001", run_seq=1, stn_cd="S1", arr_at=None, dep_at=t(5, 14)),
            dict(run_ymd=d, trn_no="00001", run_seq=2, stn_cd="S2", arr_at=t(6, 18), dep_at=None),
            dict(run_ymd=d, trn_no="00003", run_seq=1, stn_cd="S1", arr_at=None, dep_at=t(6, 0))]  # 도착 행 없음
    await insert_info(info)
    n = await rail.compute_day(d, plan, info)
    assert n == 2
    rows = {r["trn_no"]: r for r in await db.fetch("SELECT * FROM rail.train_punctuality")}
    assert float(rows["00001"]["arr_delay_min"]) == 8.0 and rows["00001"]["on_time"] is False
    assert rows["00003"]["status"] == "UNVERIFIED" and rows["00003"]["on_time"] is None
    trips = await db.fetch("SELECT * FROM rail.od_trips('S1', 'S2', %s, %s)", (d, d))
    assert len(trips) == 1 and trips[0]["arr_basis"] == "EXACT" and float(trips[0]["arr_delay_min"]) == 8.0
    # 재실행 멱등
    await rail.compute_day(d, plan, info)
    assert await count("SELECT count(*) AS n FROM rail.train_punctuality") == 2


async def insert_info(info: list[dict]) -> None:
    await db.executemany("""INSERT INTO rail.run_info (run_ymd, trn_no, run_seq, stn_cd, arr_at, dep_at)
                            VALUES (%s, %s, %s, %s, %s, %s) ON CONFLICT DO NOTHING""",
                         [(i["run_ymd"], i["trn_no"], i["run_seq"], i["stn_cd"], i["arr_at"], i["dep_at"]) for i in info])


async def test_od_trips_matches_python_reference(seeded):
    """SQL 함수 rail.od_trips 와 Python 기준 구현 corridor_trip()(P-i1)이 같은 값을 낸다 — 두 구현의 계약."""
    from roadrail.analytics.punctuality import corridor_trip, train_punctuality
    d = dt.date(2026, 9, 22)

    def t(h, m):
        return dt.datetime(2026, 9, 22, h, m, tzinfo=KST)
    plan = dict(run_ymd=d, trn_no="00101", dep_stn_cd="SEL", arr_stn_cd="BSN", plan_dep_at=t(5, 13), plan_arr_at=t(7, 50))
    stops = [dict(run_ymd=d, trn_no="00101", run_seq=1, stn_cd="SEL", arr_at=None, dep_at=t(5, 14)),
             dict(run_ymd=d, trn_no="00101", run_seq=2, stn_cd="DJN", arr_at=t(6, 12), dep_at=t(6, 15)),
             dict(run_ymd=d, trn_no="00101", run_seq=3, stn_cd="DGU", arr_at=t(7, 0), dep_at=t(7, 2)),
             dict(run_ymd=d, trn_no="00101", run_seq=4, stn_cd="BSN", arr_at=t(7, 57), dep_at=None)]
    await insert_info(stops)
    await rail.compute_day(d, [plan], stops)
    tp = train_punctuality(plan, stops)
    for a, b in [("SEL", "DJN"), ("DJN", "DGU"), ("DJN", "BSN"), ("SEL", "BSN"), ("DGU", "BSN")]:
        ref = corridor_trip(stops, a, b, tp)
        got = (await db.fetch("SELECT * FROM rail.od_trips(%s, %s, %s, %s)", (a, b, d, d)))[0]
        assert (got["dep_basis"], got["arr_basis"]) == (ref.dep_basis, ref.arr_basis), (a, b)
        assert abs(float(got["dep_delay_min"]) - ref.dep_delay_min) <= 0.1, (a, b)
        assert abs(float(got["arr_delay_min"]) - ref.arr_delay_min) <= 0.1, (a, b)
        assert float(got["ride_min"]) == ref.ride_min
    assert await db.fetch("SELECT * FROM rail.od_trips('DJN', 'SEL', %s, %s)", (d, d)) == []  # 역방향 없음


async def test_api_call_log_masks_key(seeded, monkeypatch):
    from roadrail.core.config import settings
    monkeypatch.setattr(settings(), "ex_api_key", "SECRET123")
    today = now_kst().strftime("%Y%m%d")
    ctx = ctx_with(fake_ex(today))
    await road.collect_travel_time(ctx, full=True)
    assert ctx.api_calls and all("SECRET123" not in json.dumps(c, ensure_ascii=False) for c in ctx.api_calls)
    assert all('"key": "***"' in c[3] for c in ctx.api_calls)


async def test_timeout_is_named_and_shows_in_run_detail(seeded):
    # httpx 시간 초과는 str(e) 가 비어 있다 → 예외 이름이 오류 메시지와 오류 상세에 남아야 한다
    from roadrail.providers.base import ProviderError
    from roadrail.scheduler.jobs import run_detail

    def handler(request):
        raise httpx.ReadTimeout("")

    ctx = ctx_with(handler)
    with pytest.raises(ProviderError) as ei:
        await ctx.get_json("AIRKOREA", "getCtprvnRltmMesureDnsty", "https://example.test/air", {"sidoName": "강원"}, retries=0)
    assert "ReadTimeout" in str(ei.value)
    detail = run_detail("air_quality_sido", "SCHEDULE", "PARTIAL", "PARTIAL: 강원", None, ctx)
    assert "[실패한 외부 호출 1건]" in detail and "ReadTimeout" in detail


async def test_restart_recovers_stale_runs_locks_and_reservations(seeded):
    # 강제 종료된 이전 프로세스의 흔적: RUNNING 실행 · 잠금 · 환불 못 한 예약
    from roadrail.scheduler.jobs import ABORTED_MESSAGE, recover_after_restart
    await db.execute("INSERT INTO ops.job_run (job_name, trigger, status) VALUES ('kakao_eta', 'SCHEDULE', 'RUNNING')")
    await db.execute("UPDATE ops.collect_job SET last_status = 'RUNNING' WHERE job_name = 'kakao_eta'")
    r = rds.client()
    day = now_kst().strftime("%Y%m%d")
    await r.set(rds.lock_key("kakao_eta"), "old", ex=1800)
    await r.set(rds.quota_key("KAKAO", day), 40)
    await r.set(f"quota:used:KAKAO:{day}", 25)
    assert await recover_after_restart(["KAKAO"]) == 1
    run = await db.fetchone("SELECT status, message, detail FROM ops.job_run WHERE job_name = 'kakao_eta' ORDER BY run_id DESC LIMIT 1")
    assert run["status"] == "FAILED" and run["message"] == ABORTED_MESSAGE and "끝을 기록하지 못함" in run["detail"]
    assert (await db.fetchone("SELECT last_status FROM ops.collect_job WHERE job_name = 'kakao_eta'"))["last_status"] == "FAILED"
    assert await r.get(rds.lock_key("kakao_eta")) is None
    assert int(await r.get(rds.quota_key("KAKAO", day))) == 25


async def test_cancelled_job_is_recorded_as_aborted_and_unlocked(seeded, monkeypatch):
    # SIGTERM 으로 작업이 취소되면 'OK' 가 아니라 중단으로 기록하고 잠금을 푼다
    import asyncio

    from roadrail.scheduler import jobs

    started = asyncio.Event()

    async def slow(ctx):
        started.set()
        await asyncio.sleep(60)

    monkeypatch.setitem(jobs.JOBS, "maintenance", jobs.JobSpec(slow, jobs._const({})))
    task = asyncio.create_task(jobs.run_job("maintenance", "ADMIN"))
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    run = await db.fetchone("SELECT status, message FROM ops.job_run WHERE job_name = 'maintenance' ORDER BY run_id DESC LIMIT 1")
    assert run["status"] == "FAILED" and run["message"] == jobs.ABORTED_MESSAGE
    assert await rds.client().get(rds.lock_key("maintenance")) is None


async def test_rail_geometry_skips_when_fresh_on_schedule(seeded):
    # 매일 05:00 확인하되 28일 안에 계산했으면 호출 없이 건너뛴다
    from roadrail.pipeline import rail_geometry
    await db.execute("""INSERT INTO ref.rail_link (dep_stn_cd, arr_stn_cd, path, length_km, straight_km)
                        VALUES ('A', 'B', '[[37,127],[36,127]]', 111, 111)""")
    ctx = ctx_with(lambda req: (_ for _ in ()).throw(AssertionError("호출하면 안 됨")))
    ctx.trigger = "SCHEDULE"
    assert await rail_geometry.build_rail_links(ctx) == 0
    assert ctx.calls == 0 and "건너뜀" in ctx.notes[0]


async def test_od_trips_prefers_timetable_over_interpolation(seeded):
    """중간역 쌍: TAGO 시간표(rail.tt_plan)가 있으면 보간(EST) 대신 실제 계획 시각과 정확 비교(TT) · 차종 반환 (V11)."""
    d = dt.date(2026, 9, 22)

    def t(h, m):
        return dt.datetime(2026, 9, 22, h, m, tzinfo=KST)
    plan = dict(run_ymd=d, trn_no="00101", dep_stn_cd="SEL", arr_stn_cd="BSN", plan_dep_at=t(5, 13), plan_arr_at=t(7, 50))
    stops = [dict(run_ymd=d, trn_no="00101", run_seq=1, stn_cd="SEL", arr_at=None, dep_at=t(5, 14)),
             dict(run_ymd=d, trn_no="00101", run_seq=2, stn_cd="DJN", arr_at=t(6, 12), dep_at=t(6, 15)),
             dict(run_ymd=d, trn_no="00101", run_seq=3, stn_cd="DGU", arr_at=t(7, 0), dep_at=t(7, 2)),
             dict(run_ymd=d, trn_no="00101", run_seq=4, stn_cd="BSN", arr_at=t(7, 57), dep_at=None)]
    await insert_info(stops)
    await rail.compute_day(d, [plan], stops)
    before = (await db.fetch("SELECT * FROM rail.od_trips('DJN', 'DGU', %s, %s)", (d, d)))[0]
    assert (before["dep_basis"], before["arr_basis"], before["grade"]) == ("EST", "EST", None)
    # 시간표: 대전 06:10 출발 · 동대구 06:52 도착 → 실제 06:15 · 07:00 은 각각 5분 · 8분 지연
    await db.execute("""INSERT INTO rail.tt_plan (dep_stn_cd, arr_stn_cd, dep_date, trn_no, plan_dep_at, plan_arr_at, grade)
                        VALUES ('DJN', 'DGU', %s, '00101', %s, %s, 'KTX-산천')""", (d, t(6, 10), t(6, 52)))
    got = (await db.fetch("SELECT * FROM rail.od_trips('DJN', 'DGU', %s, %s)", (d, d)))[0]
    assert (got["dep_basis"], got["arr_basis"], got["grade"]) == ("TT", "TT", "KTX-산천")
    assert float(got["dep_delay_min"]) == 5.0 and float(got["arr_delay_min"]) == 8.0
    assert got["est_plan_arr_at"] == t(6, 52)
    # 시발역은 코레일 운행계획과의 정확 비교(EXACT)가 우선
    await db.execute("""INSERT INTO rail.tt_plan (dep_stn_cd, arr_stn_cd, dep_date, trn_no, plan_dep_at, plan_arr_at, grade)
                        VALUES ('SEL', 'DGU', %s, '00101', %s, %s, 'KTX')""", (d, t(5, 13), t(6, 52)))
    got = (await db.fetch("SELECT * FROM rail.od_trips('SEL', 'DGU', %s, %s)", (d, d)))[0]
    assert (got["dep_basis"], got["arr_basis"]) == ("EXACT", "TT")


async def test_holiday_sync_is_idempotent(seeded, fixtures_dir):
    from roadrail.pipeline import holidays
    body = (fixtures_dir / "kasi" / "rest_days_2026.json").read_text()

    def handler(request):
        return httpx.Response(200, text=body, headers={"content-type": "application/json"})

    for _ in range(2):
        ctx = ctx_with(handler)
        await holidays.sync_holidays(ctx)
    assert ctx.calls == 3  # 작년 · 올해 · 내년
    assert await count("SELECT count(*) AS n FROM ref.holiday") == 22  # 같은 응답을 세 번 받아도 날짜당 한 행
    assert dt.date(2026, 9, 24) in await holidays.holiday_days(dt.date(2026, 1, 1))
