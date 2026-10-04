"""값 영역 제약 (L11) — 코드가 쓰는 값만 들어가고, 오타 · 새 값은 저장 시점에 막힌다."""
from __future__ import annotations

import psycopg
import pytest

from roadrail.core import db

BAD = [
    ("INSERT INTO ops.slot_gap (job_name, series_key, slot_ts, reason) VALUES ('t', 'k', now(), 'NO_SAMPLE')",
     "slot_gap_reason_check"),
    ("INSERT INTO ops.job_run (job_name, trigger, status) VALUES ('t', 'ADMIN', 'DONE')", "job_run_status_check"),
    ("""INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, content, source)
        VALUES ('h-schema', now(), 'U1', '사고', '시험', 'POLICE')""", "road_incident_source_check"),
    ("""INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, content, lat, lon)
        VALUES ('h-schema', now(), 'U1', '사고', '시험', 127.1, 37.5)""", "road_incident_coords_check"),  # 경위도 뒤바뀜
    ("INSERT INTO ref.station (stn_cd, stn_nm, lat, lon, source) VALUES ('X-schema', '시험', 95, 127, 'KAKAO')",
     "station_coords_check"),
]


@pytest.mark.parametrize(("sql", "constraint"), BAD, ids=[c for _, c in BAD])
async def test_values_outside_the_domain_are_rejected(sql, constraint):
    with pytest.raises(psycopg.errors.CheckViolation) as e:
        await db.execute(sql)
    assert e.value.diag.constraint_name == constraint


async def test_values_the_code_writes_are_accepted():
    try:
        for reason in ("NO_DATA", "LOW_COVERAGE", "SOURCE_EXPIRED", "NO_SAMPLES", None):
            await db.execute("""INSERT INTO ops.slot_gap (job_name, series_key, slot_ts, reason)
                                VALUES ('schema-test', %s, now(), %s)""", (str(reason), reason))
        for status in ("RUNNING", "OK", "PARTIAL", "FAILED", "SKIPPED_QUOTA"):
            await db.execute("INSERT INTO ops.job_run (job_name, trigger, status) VALUES ('schema-test', 'ADMIN', %s)",
                             (status,))
    finally:
        await db.execute("DELETE FROM ops.slot_gap WHERE job_name = 'schema-test'")
        await db.execute("DELETE FROM ops.job_run WHERE job_name = 'schema-test'")
