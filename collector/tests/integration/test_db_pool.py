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


class StallProxy:
    """DB 앞의 TCP 프록시 — stall() 뒤에는 연결을 열어 둔 채 아무것도 넘기지 않는다(DB 일시 정지 · 응답 없는 서버와 같은 효과)."""

    def __init__(self, host: str, port: int):
        self.target = (host, port)
        self.stalled = asyncio.Event()
        self.never = asyncio.Event()
        self.writers: list[asyncio.StreamWriter] = []

    async def start(self) -> int:
        self.server = await asyncio.start_server(self._accept, "127.0.0.1", 0)
        return self.server.sockets[0].getsockname()[1]

    def stall(self) -> None:
        self.stalled.set()

    async def _accept(self, cr: asyncio.StreamReader, cw: asyncio.StreamWriter) -> None:
        sr, sw = await asyncio.open_connection(*self.target)
        self.writers += [cw, sw]
        await asyncio.gather(self._pipe(cr, sw), self._pipe(sr, cw), return_exceptions=True)

    async def _pipe(self, r: asyncio.StreamReader, w: asyncio.StreamWriter) -> None:
        while data := await r.read(65536):
            if self.stalled.is_set():
                await self.never.wait()   # 받은 것을 넘기지 않고 붙잡아 둔다 — 연결은 끊지 않음
            w.write(data)
            await w.drain()

    async def close(self) -> None:
        self.server.close()
        for w in self.writers:
            w.close()


async def test_qa11_a_query_gives_up_within_the_limit_when_the_database_stops_responding(monkeypatch):
    # QA-11: 수집기 DB 조회에는 시간 제한이 없었다 — DB 가 멈추면 작업이 끝없이 기다리고(잠금 TTL 이 지나면 다음 실행도 같이 매달림),
    # 풀(최대 8)이 바닥나 모든 작업이 멈춘다. API 는 QA-02 로 15초 제한
    from psycopg.conninfo import conninfo_to_dict, make_conninfo

    from roadrail.core.config import settings
    info = conninfo_to_dict(os.environ["DATABASE_URL"])
    proxy = StallProxy(info.get("host", "localhost"), int(info.get("port", 5432)))
    port = await proxy.start()
    monkeypatch.setenv("DATABASE_URL", make_conninfo(os.environ["DATABASE_URL"], host="127.0.0.1", port=port))
    monkeypatch.setenv("DB_STATEMENT_TIMEOUT_S", "2")
    settings.cache_clear()
    await db.close()
    try:
        assert (await db.fetchone("SELECT 1 AS ok"))["ok"] == 1
        proxy.stall()
        t0 = asyncio.get_running_loop().time()
        try:
            await asyncio.wait_for(db.fetchone("SELECT 1 AS ok"), 30)
        except TimeoutError:
            pass
        elapsed = asyncio.get_running_loop().time() - t0
        assert elapsed < 20, f"DB 가 멈추자 조회가 {elapsed:.0f}초 넘게 기다렸다(제한 없음)"
    finally:
        await db.close()
        await proxy.close()
        monkeypatch.undo()
        settings.cache_clear()
