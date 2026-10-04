"""연결 풀 — DB 가 다시 시작돼도 다음 작업이 끊긴 연결을 받지 않는다."""
from __future__ import annotations

import asyncio
import os

import psycopg

from roadrail.core import db


async def test_qa10_a_connection_killed_by_a_database_restart_is_not_handed_out():
    # QA-10: DB 컨테이너를 다시 만든 직후(22:20) 예약 작업 3개가 job_run 을 남기기도 전에 AdminShutdown 으로 실패 —
    # 풀에 쉬고 있던 연결은 서버가 이미 끊었는데 풀이 확인하지 않고 내줬다
    pid = (await db.fetchone("SELECT pg_backend_pid() AS pid"))["pid"]
    with psycopg.connect(os.environ["DATABASE_URL"], autocommit=True) as admin:   # 재시작과 같은 효과: 서버가 그 연결을 끊음
        admin.execute("SELECT pg_terminate_backend(%s)", (pid,))
    await asyncio.sleep(0.2)
    assert (await db.fetchone("SELECT 1 AS ok"))["ok"] == 1
