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
from roadrail.providers import ex
from roadrail.providers.base import JobContext, ProviderError
from roadrail.providers.kma import latlon_to_grid
from roadrail.scheduler.quota import QuotaBudget, QuotaExhausted

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
    assert await rds.client().get(rds.tail_key("101", "103")) == f"{today}:5"


async def test_tail_cursor_advances_only_for_saved_rows(seeded):
    # 셋째 구간에서 예산이 떨어져 작업이 실패해도, 받은 구간은 저장하고 그 구간만 꼬리 위치를 옮긴다 (BUG-05).
    # 예전에는 꼬리 위치를 저장 전에 옮기고 실패 시 행을 버려, 다음 수집이 그 슬롯들을 건너뛰었다.
    today = now_kst().strftime("%Y%m%d")
    ctx = JobContext(job_name="road_travel_time", http=httpx.AsyncClient(transport=httpx.MockTransport(fake_ex(today))),
                     budget=QuotaBudget(rds.client(), lambda p: 2))
    with pytest.raises(QuotaExhausted):
        await road.collect_travel_time(ctx)
    saved = {(r["start_unit_code"], r["end_unit_code"]) for r in
             await db.fetch("SELECT DISTINCT start_unit_code, end_unit_code FROM ts.road_travel_time")}
    assert len(saved) == 2  # 호출 예산 2건 = 받은 두 구간
    for seg in (("101", "103"), ("103", "528"), ("528", "101")):
        tail = await rds.client().get(rds.tail_key(*seg))
        assert (tail is not None) == (seg in saved), seg  # 저장 안 된 구간은 다음 수집이 처음부터


async def test_tail_cursor_carries_the_day_of_the_data_not_the_wall_clock(seeded, monkeypatch):
    # 자정을 넘긴 수집이 D일 데이터를 읽으면 커서도 D일 것이어야 한다 — 벽시계(D+1)로 기억하면 원천이 D+1 로 넘어간 뒤
    # D 의 행 수만큼 건너뛴 쪽을 요청해 새 날 슬롯을 놓친다(H1 · 예전 회귀: 읽기 · 쓰기가 now_kst 를 따로 불렀다)
    real_today = now_kst()
    day = real_today.strftime("%Y%m%d")
    after_midnight = real_today.replace(hour=0, minute=0, second=10, microsecond=0) + dt.timedelta(days=1)
    monkeypatch.setattr(road, "now_kst", lambda: after_midnight)
    await road.collect_travel_time(ctx_with(fake_ex(day)))
    for seg in (("101", "103"), ("103", "528"), ("528", "101")):
        assert await rds.client().get(rds.tail_key(*seg)) == f"{day}:5", seg


def fake_ex_source(state: dict):
    """원천이 '지금 주는 날'(state["day"])과 그날 1종 5분 행 수(state["slots"])를 바꿀 수 있는 가짜 realUnitTrtm.
    실제 API 처럼 1종 → 다음 차종 순으로 99행씩 쪽을 나누고, 마지막 쪽 뒤를 요청하면 빈 목록을 준다."""
    def handler(request: httpx.Request) -> httpx.Response:
        q = request.url.params
        s, e, page = q["iStartUnitCode"], q["iEndUnitCode"], int(q["pageNo"])
        state["calls"].append((s, e, page))
        drop = state.get("drop", set())
        items = [{"startUnitCode": s, "endUnitCode": e, "stdDate": state["day"], "stdTime": f"{m // 60:02d}:{m % 60:02d}",
                  "timeAvg": "10.0", "timeMin": "8.0", "timeMax": "12.0", "efcvTrfl": "50", "tcsCarTypeCode": "1"}
                 for m in range(0, state["slots"] * 5, 5) if f"{m // 60:02d}:{m % 60:02d}" not in drop]
        items.append({"startUnitCode": s, "endUnitCode": e, "stdDate": state["day"], "stdTime": "00:00",
                      "timeAvg": "20", "tcsCarTypeCode": "2"})
        pages = -(-len(items) // ex.PAGE)
        chunk = items[(page - 1) * ex.PAGE: page * ex.PAGE]
        return httpx.Response(200, json={"count": len(chunk), "pageNo": page, "pageSize": pages,
                                         "realUnitTrtmVO": chunk, "code": "SUCCESS"})
    return handler


async def test_tail_cursor_restarts_when_the_source_switches_to_the_new_day(seeded, monkeypatch):
    # H1: 도로공사 API 는 자정 뒤 약 01:30 까지 전날(D)을 계속 준다. 그 사이 받은 D 의 행 수를 D+1 의 커서로 기억하면,
    # 원천이 D+1 로 넘어간 뒤 3쪽부터 요청해 D+1 아침 슬롯을 하나도 받지 못했다
    # (실측 9/25: 101→103 구간이 00:12 부터 3쪽 · 01:31~10:01 4쪽을 요청 — 00~07시 슬롯은 10시 백필로야 저장).
    d1 = now_kst().replace(hour=0, minute=0, second=0, microsecond=0)
    d = d1 - dt.timedelta(days=1)
    state = {"day": d.strftime("%Y%m%d"), "slots": 280, "calls": []}
    clock = {"now": d1 + dt.timedelta(minutes=10)}
    monkeypatch.setattr(road, "now_kst", lambda: clock["now"])
    await road.collect_travel_time(ctx_with(fake_ex_source(state)))     # D+1 00:10 — 원천은 아직 D (3쪽)
    state.update(day=d1.strftime("%Y%m%d"), slots=20, calls=[])
    clock["now"] = d1 + dt.timedelta(hours=2)
    await road.collect_travel_time(ctx_with(fake_ex_source(state)))     # D+1 02:00 — 원천이 D+1 로 넘어감 (1쪽)
    n = (await db.fetchone("SELECT count(*) AS n FROM ts.road_travel_time WHERE slot_ts >= %s", (d1,)))["n"]
    assert n == 3 * 20, state["calls"]


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


async def test_yesterdays_gap_is_backfilled_while_the_source_still_serves_yesterday(seeded, monkeypatch):
    # M2: 원천은 자정 뒤 약 01:30 까지 전날을 준다. 예전에는 00:07 백필이 전날 결측을 무조건 '원천 만료'로 닫아
    # 그 사이 채울 수 있던 전날 슬롯(예: 21시)을 다시 받지 않았다.
    d1 = now_kst().replace(hour=0, minute=0, second=0, microsecond=0)
    d = d1 - dt.timedelta(days=1)
    clock = {"now": d.replace(hour=23, minute=30)}
    monkeypatch.setattr(road, "now_kst", lambda: clock["now"])
    state = {"day": d.strftime("%Y%m%d"), "slots": 264, "calls": [], "drop": {"21:00"}}   # 00:00~21:55, 21:00 없음
    await road.collect_travel_time(ctx_with(fake_ex_source(state)), full=True)
    gap = await db.fetchone("SELECT backfilled_at FROM ops.slot_gap WHERE series_key = 'TST:DN' AND slot_ts = %s",
                            (d + dt.timedelta(hours=21),))
    assert gap is not None and gap["backfilled_at"] is None

    clock["now"] = d1 + dt.timedelta(minutes=20)          # D+1 00:20 — 원천은 아직 D 를 주고, 21:00 이 공개됨
    state.update(drop=set(), calls=[])
    ctx = ctx_with(fake_ex_source(state))
    ctx.job_name = "road_gap_backfill"
    await road.backfill_gaps(ctx)
    gap = await db.fetchone("SELECT backfilled_at, reason FROM ops.slot_gap WHERE series_key = 'TST:DN' AND slot_ts = %s",
                            (d + dt.timedelta(hours=21),))
    assert gap["backfilled_at"] is not None, (gap, state["calls"])


async def test_yesterdays_gap_expires_once_the_source_has_moved_on(seeded, monkeypatch):
    d1 = now_kst().replace(hour=0, minute=0, second=0, microsecond=0)
    d = d1 - dt.timedelta(days=1)
    clock = {"now": d.replace(hour=23, minute=30)}
    monkeypatch.setattr(road, "now_kst", lambda: clock["now"])
    state = {"day": d.strftime("%Y%m%d"), "slots": 264, "calls": [], "drop": {"21:00"}}
    await road.collect_travel_time(ctx_with(fake_ex_source(state)), full=True)
    clock["now"] = d1 + dt.timedelta(hours=2, minutes=7)  # D+1 02:07 — 원천이 D+1 로 넘어감
    state.update(day=d1.strftime("%Y%m%d"), slots=12, drop=set(), calls=[])
    ctx = ctx_with(fake_ex_source(state))
    ctx.job_name = "road_gap_backfill"
    await road.backfill_gaps(ctx)
    gap = await db.fetchone("SELECT backfilled_at, reason FROM ops.slot_gap WHERE series_key = 'TST:DN' AND slot_ts = %s",
                            (d + dt.timedelta(hours=21),))
    assert gap["reason"] == "SOURCE_EXPIRED" and gap["backfilled_at"] is None


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
    assert ctx.api_calls and all("SECRET123" not in json.dumps(c, ensure_ascii=False, default=str) for c in ctx.api_calls)
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


async def test_failure_before_the_job_body_releases_lock_and_records_run(seeded, monkeypatch):
    # 잠금을 잡은 뒤 예산 예상치 조회가 실패하면 예전에는 잠금이 TTL(최대 2시간)까지 남고 실행 기록이 RUNNING 으로 남았다 (BUG-06)
    from roadrail.pipeline import analysis
    from roadrail.scheduler import jobs

    async def broken_estimate():
        raise RuntimeError("예상치 조회 실패")
    monkeypatch.setitem(jobs.JOBS, "maintenance", jobs.JobSpec(analysis.maintenance, broken_estimate))
    assert await jobs.run_job("maintenance", "ADMIN") == "FAILED"
    assert await rds.client().get(rds.lock_key("maintenance")) is None
    r = await db.fetchone("SELECT status, message FROM ops.job_run WHERE job_name = 'maintenance' ORDER BY run_id DESC LIMIT 1")
    assert r["status"] == "FAILED" and "예상치 조회 실패" in r["message"]


async def test_lock_release_does_not_delete_someone_elses_lock(seeded, monkeypatch):
    # 잠금이 만료돼 다른 실행이 잡은 경우 끝날 때 그 잠금을 지우면 안 된다 (비교 후 삭제, BUG-06)
    from roadrail.scheduler import jobs

    async def body(ctx):
        await rds.client().set(rds.lock_key("maintenance"), "someone-else")
    monkeypatch.setitem(jobs.JOBS, "maintenance", jobs.JobSpec(body, jobs._const({})))
    assert await jobs.run_job("maintenance", "ADMIN") == "OK"
    assert await rds.client().get(rds.lock_key("maintenance")) == "someone-else"


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


async def test_utic_incidents_are_upserted_with_source_and_key_masked(fixtures_dir, monkeypatch):
    from roadrail.core.config import settings
    monkeypatch.setattr(settings(), "utic_api_key", "SECRET456")
    body = (fixtures_dir / "utic" / "ims.xml").read_text()
    urls = []

    def handler(request):
        urls.append(request.url)
        return httpx.Response(200, text=body, headers={"content-type": "text/xml;charset=utf-8"})

    await db.execute("DELETE FROM ts.road_incident WHERE source = 'UTIC'")
    ctx = ctx_with(handler)
    assert await road.collect_utic_incidents(ctx) == 5
    assert await road.collect_utic_incidents(ctx) == 5                     # 같은 목록을 다시 받아도 행은 그대로 (멱등)
    rows = await db.fetch("SELECT type_name, end_at, lane, lat FROM ts.road_incident WHERE source = 'UTIC' ORDER BY type_code")
    assert len(rows) == 5
    assert [r["type_name"] for r in rows] == ["사고", "사고", "공사", "통제", "행사"]
    assert rows[0]["end_at"] is not None and rows[0]["lane"] == "차로"
    assert urls[0].scheme == "https" and urls[0].params["key"] == "SECRET456"
    assert all("SECRET456" not in c[3] for c in ctx.api_calls)    # 호출 기록에는 키를 가린다
    assert ctx.notes[-1].startswith("UTIC 돌발 5건")


async def test_utic_key_error_is_reported_with_its_code_and_not_retried(monkeypatch):
    # 키가 틀리거나 만료되면 HTTP 200 + JSON 오류 본문 (실측) — 'XML 아님' 이 아니라 기관의 오류 코드 · 문구를 남긴다
    from roadrail.core.config import settings
    monkeypatch.setattr(settings(), "utic_api_key", "SECRET456")
    calls = []

    def handler(request):
        calls.append(request.url)
        return httpx.Response(200, text='[{"resultCode":"02","resultMsg":"유효한 KEY값이 아닙니다."}]',
                              headers={"content-type": "application/json;charset=UTF-8"})

    ctx = ctx_with(handler)
    with pytest.raises(ProviderError) as e:
        await road.collect_utic_incidents(ctx)
    assert e.value.code == "02" and "유효한 KEY값이 아닙니다" in e.value.detail
    assert len(calls) == 1                                                  # 키 오류는 다시 불러도 같다 — 재시도 안 함
    assert ctx.api_calls[-1][5] == "02"                                     # 호출 기록의 결과 코드


async def test_utic_without_key_makes_no_call(monkeypatch):
    from roadrail.core.config import settings
    monkeypatch.setattr(settings(), "utic_api_key", "")

    def handler(request):
        raise AssertionError("키 없이 호출하면 안 된다")

    assert await road.collect_utic_incidents(ctx_with(handler)) == 0


async def test_job_message_is_masked_like_the_detail(seeded, monkeypatch):
    # L4: 오류 상세(detail)는 마스킹했지만 작업 메시지(job_run.message · collect_job.last_message)는 원문 그대로였다
    from roadrail.core.config import settings
    from roadrail.scheduler import jobs
    monkeypatch.setattr(settings(), "ex_api_key", "SECRET777")

    async def body(ctx):
        raise RuntimeError("upstream said ?key=SECRET777 is wrong")
    monkeypatch.setitem(jobs.JOBS, "maintenance", jobs.JobSpec(body, jobs._const({})))
    assert await jobs.run_job("maintenance", "ADMIN") == "FAILED"
    run = await db.fetchone("SELECT message, detail FROM ops.job_run WHERE job_name = 'maintenance' ORDER BY run_id DESC LIMIT 1")
    job = await db.fetchone("SELECT last_message FROM ops.collect_job WHERE job_name = 'maintenance'")
    assert "SECRET777" not in run["message"] and "SECRET777" not in job["last_message"]
    assert "SECRET777" not in run["detail"]


async def test_redirects_are_not_followed_while_keys_ride_in_the_query(monkeypatch):
    # L6: 키가 쿼리에 있는데 리다이렉트를 따라가면 다른 곳(또는 http)으로 키가 넘어갈 수 있다
    from roadrail.scheduler import jobs
    assert jobs.http().follow_redirects is False
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        return httpx.Response(302, headers={"Location": "http://elsewhere.example/?key=SECRET1"})
    with pytest.raises(ProviderError, match="리다이렉트"):
        await ctx_with(handler).get_json("KMA", "x", "https://apis.data.go.kr/x", {"serviceKey": "SECRET1"})
    assert len(seen) == 1


async def test_oversized_response_is_cut_off(monkeypatch):
    # L5: 응답 크기 상한이 없었다(XML 은 다 받은 뒤 길이를 봤고 JSON 은 상한 없음)
    from roadrail.providers import base
    monkeypatch.setitem(base.MAX_BYTES, "KMA", 1000)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"pad": "x" * 5000})
    with pytest.raises(ProviderError, match="너무 큼"):
        await ctx_with(handler).get_json("KMA", "x", "https://apis.data.go.kr/x", {})


async def test_gap_that_survives_a_full_refetch_is_marked_no_samples_and_not_retried(seeded, monkeypatch):
    # M3: 새벽엔 원천 표본이 원래 적어 길 슬롯이 '결측'으로 남는다. 다시 받아도 채울 수 없는데 2시간마다 6번씩 전체를
    # 다시 받았다(실측 하루 약 900건). 공개 지연(약 3시간)이 지난 뒤 전체를 다시 받아도 비어 있으면 NO_SAMPLES 로 두고 그만 받는다.
    d1 = now_kst().replace(hour=0, minute=0, second=0, microsecond=0)
    clock = {"now": d1 + dt.timedelta(hours=1)}
    monkeypatch.setattr(road, "now_kst", lambda: clock["now"])
    state = {"day": d1.strftime("%Y%m%d"), "slots": 12, "calls": [], "drop": {"00:10"}}   # 00:00~00:55, 00:10 은 원천에 없음
    await road.collect_travel_time(ctx_with(fake_ex_source(state)), full=True)
    clock["now"] = d1 + dt.timedelta(hours=6, minutes=7)  # 06:07 백필 — 00:10 은 공개 지연이 한참 지났다
    ctx = ctx_with(fake_ex_source(state))
    ctx.job_name = "road_gap_backfill"
    await road.backfill_gaps(ctx)
    gap = await db.fetchone("SELECT reason, backfilled_at FROM ops.slot_gap WHERE series_key = 'TST:DN' AND slot_ts = %s",
                            (d1 + dt.timedelta(minutes=10),))
    assert gap["reason"] == "NO_SAMPLES" and gap["backfilled_at"] is None
    state["calls"] = []
    clock["now"] = d1 + dt.timedelta(hours=8, minutes=7)
    ctx = ctx_with(fake_ex_source(state))
    ctx.job_name = "road_gap_backfill"
    await road.backfill_gaps(ctx)
    assert state["calls"] == []                            # 다시 받지 않는다


async def test_volume_keeps_the_first_collected_at_when_seen_again(seeded, fixtures_dir):
    # L1: 전국 교통량은 15분 슬롯을 네 번씩 다시 준다. 다시 받을 때마다 collected_at 을 갱신해
    # '공개 지연'(처음 본 시각 − 슬롯)이 152분으로 부풀려졌다(실제 약 80분)
    body = json.loads((fixtures_dir / "ex" / "traffic_all.json").read_text())

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)
    await db.execute("TRUNCATE ts.road_volume")
    await road.collect_volume(ctx_with(handler))
    first = await db.fetchone("SELECT min(collected_at) AS a, max(collected_at) AS b FROM ts.road_volume")
    await road.collect_volume(ctx_with(handler))
    again = await db.fetchone("SELECT min(collected_at) AS a, max(collected_at) AS b FROM ts.road_volume")
    assert again == first


async def test_api_call_log_keeps_each_call_time(seeded, monkeypatch):
    # L2: 호출 기록을 작업 끝에 한꺼번에 넣어 called_at 이 모두 같은 시각(끝난 시각)이었다
    import asyncio

    from roadrail.scheduler import jobs
    monkeypatch.setattr(jobs, "http", lambda: httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json={"response": {"header": {"resultCode": "00"}, "body": {}}}))))

    async def body(ctx):
        await ctx.get_json("KMA", "a", "https://apis.data.go.kr/a", {})
        await asyncio.sleep(0.3)
        await ctx.get_json("KMA", "b", "https://apis.data.go.kr/b", {})
    monkeypatch.setitem(jobs.JOBS, "maintenance", jobs.JobSpec(body, jobs._const({"KMA": 2})))
    assert await jobs.run_job("maintenance", "ADMIN") == "OK"
    rows = await db.fetch("SELECT endpoint, called_at FROM ops.api_call WHERE job_name = 'maintenance' ORDER BY endpoint")
    assert [r["endpoint"] for r in rows] == ["a", "b"]
    assert (rows[1]["called_at"] - rows[0]["called_at"]).total_seconds() >= 0.25


async def test_unchanged_rows_are_not_rewritten(seeded, fixtures_dir):
    # L8: 꼬리를 다시 받을 때마다 같은 값도 다시 써(갱신이 삽입의 2배 이상) 죽은 튜플 · WAL 만 늘었다.
    # 값이 같으면 행을 건드리지 않는다(xmin 그대로), 값이 바뀌면 갱신한다
    today = now_kst().strftime("%Y%m%d")
    tt = "SELECT string_agg(xmin::text, ',' ORDER BY slot_ts, start_unit_code) AS x FROM ts.road_travel_time"
    corr = "SELECT string_agg(xmin::text, ',' ORDER BY slot_ts, direction) AS x FROM ts.road_corridor_tt"
    await road.collect_travel_time(ctx_with(fake_ex(today)), full=True)
    t1, c1 = (await db.fetchone(tt))["x"], (await db.fetchone(corr))["x"]
    await road.collect_travel_time(ctx_with(fake_ex(today)), full=True)
    assert (await db.fetchone(tt))["x"] == t1
    assert (await db.fetchone(corr))["x"] == c1

    body = json.loads((fixtures_dir / "ex" / "traffic_all.json").read_text())
    vol = "SELECT string_agg(xmin::text, ',' ORDER BY slot_ts, ex_div_code, tcs_type, car_type) AS x FROM ts.road_volume"
    await db.execute("TRUNCATE ts.road_volume")
    await road.collect_volume(ctx_with(lambda request: httpx.Response(200, json=body)))
    v1 = (await db.fetchone(vol))["x"]
    await road.collect_volume(ctx_with(lambda request: httpx.Response(200, json=body)))
    assert (await db.fetchone(vol))["x"] == v1
    first = body["trafficAll"][0]
    first["trafficAmout"] = str(int(first["trafficAmout"]) + 1)
    await road.collect_volume(ctx_with(lambda request: httpx.Response(200, json=body)))
    assert (await db.fetchone(vol))["x"] != v1                     # 바뀐 값은 갱신
