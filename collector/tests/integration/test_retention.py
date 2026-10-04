"""보존 기간 정리 (L7 — 사용자 결정 2026-10-04: 90일) · 기동 때 밀린 정리."""
from __future__ import annotations

import datetime as dt

import httpx

from roadrail.core import db, rds
from roadrail.core.timeutil import now_kst
from roadrail.providers.base import JobContext
from roadrail.scheduler.quota import QuotaBudget


def ctx(job: str) -> JobContext:
    return JobContext(job_name=job, http=httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(500))),
                      budget=QuotaBudget(rds.client(), lambda p: 1000))


async def test_rows_older_than_90_days_are_removed_and_recent_or_still_active_ones_kept():
    from roadrail.pipeline import analysis
    now = now_kst()
    old, recent = now - dt.timedelta(days=91), now - dt.timedelta(days=89)
    await db.execute("TRUNCATE ts.road_incident, ops.slot_gap, env.air_quality, ana.kakao_eta")
    # 돌발: 안내가 끝난 지(마지막으로 본 지) 90일 — 시작 시각이 오래돼도 아직 안내 중이면 남긴다
    await db.executemany("""INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, content, last_seen_at)
                            VALUES (%s, %s, '1', '사고', '시험', %s)""",
                         [("gone", old, old), ("active-long", now - dt.timedelta(days=400), now), ("recent", recent, recent)])
    await db.executemany("INSERT INTO ops.slot_gap (job_name, series_key, slot_ts, reason) VALUES ('road_travel_time', %s, %s, 'NO_DATA')",
                         [("A:DN", old.replace(second=0, microsecond=0)), ("A:DN", recent.replace(second=0, microsecond=0))])
    await db.executemany("""INSERT INTO env.air_quality (station_name, sido_name, data_time, pm10, pm25)
                            VALUES (%s, '서울', %s, 10, 5)""", [("old", old), ("new", recent)])
    await db.executemany("""INSERT INTO ana.kakao_eta (requested_at, depart_at, corridor_id, direction, duration_sec, distance_m)
                            VALUES (%s, %s, 'TST', 'DN', 3600, 160000)""", [(old, old), (recent, recent)])
    c = ctx("retention")
    await analysis.retention(c)
    assert sorted(r["msg_hash"].strip() for r in await db.fetch("SELECT msg_hash FROM ts.road_incident")) == ["active-long", "recent"]
    assert (await db.fetchone("SELECT count(*) AS n FROM ops.slot_gap"))["n"] == 1
    assert (await db.fetchone("SELECT count(*) AS n FROM env.air_quality"))["n"] == 1
    assert (await db.fetchone("SELECT count(*) AS n FROM ana.kakao_eta"))["n"] == 1
    assert c.notes and "90일" in c.notes[-1]


async def test_startup_runs_cleanup_jobs_that_were_missed_while_asleep():
    # 정리 작업은 정해진 시각에만 돌아 노트북이 잠들어 있으면 건너뛰었다(파티션 생성도 매달 한 번) → 기동 때 밀린 것만
    from roadrail.scheduler import main
    await db.execute("DELETE FROM ops.job_run WHERE job_name IN ('retention', 'maintenance')")
    assert set(await main.missed_cleanup()) == {"retention", "maintenance"}
    await db.execute("""INSERT INTO ops.job_run (job_name, trigger, started_at, finished_at, status)
                        VALUES ('retention', 'SCHEDULE', now() - interval '2 hours', now() - interval '2 hours', 'OK'),
                               ('maintenance', 'SCHEDULE', now() - interval '40 days', now() - interval '40 days', 'OK')""")
    assert await main.missed_cleanup() == ["maintenance"]
    await db.execute("""INSERT INTO ops.job_run (job_name, trigger, started_at, finished_at, status)
                        VALUES ('maintenance', 'SCHEDULE', now() - interval '3 days', now() - interval '3 days', 'OK')""")
    assert await main.missed_cleanup() == []
