"""PostgreSQL (psycopg 3 async pool)."""
from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from psycopg import AsyncCursor
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from .config import settings

_pool: AsyncConnectionPool | None = None


async def pool() -> AsyncConnectionPool:
    global _pool
    if _pool is None:
        # check: 내주기 전에 연결이 살아 있는지 본다 — DB 가 다시 시작되면 쉬던 연결은 서버가 이미 끊었다(QA-10)
        _pool = AsyncConnectionPool(
            settings().database_url, min_size=1, max_size=8, open=False,
            kwargs={"row_factory": dict_row, "options": "-c timezone=Asia/Seoul"},
            check=AsyncConnectionPool.check_connection,
        )
        await _pool.open(wait=True, timeout=30)
    return _pool


async def close() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


def _limit() -> asyncio.Timeout:
    """조회 하나(트랜잭션 하나)의 상한 — 연결 받기 · 확인 · 실행 · 커밋까지. DB 가 응답을 멈추면 끝없이 기다리던 것(QA-11).
    시간이 지나면 psycopg 가 서버에 취소를 보내고, 끝나지 않으면 연결을 닫는다(풀이 새로 연결)."""
    return asyncio.timeout(settings().db_statement_timeout_s)


async def fetch(sql: str, params=None) -> list[dict]:
    p = await pool()
    async with _limit(), p.connection() as conn, conn.cursor() as cur:
        await cur.execute(sql, params)
        return await cur.fetchall() if cur.description else []


async def fetchone(sql: str, params=None) -> dict | None:
    rows = await fetch(sql, params)
    return rows[0] if rows else None


async def execute(sql: str, params=None) -> int:
    p = await pool()
    async with _limit(), p.connection() as conn, conn.cursor() as cur:
        await cur.execute(sql, params)
        return cur.rowcount


async def executemany(sql: str, rows: list) -> int:
    if not rows:
        return 0
    p = await pool()
    async with _limit(), p.connection() as conn, conn.cursor() as cur:
        await cur.executemany(sql, rows)
        return len(rows)


@asynccontextmanager
async def transaction() -> AsyncIterator[AsyncCursor]:
    """한 트랜잭션의 커서 — 전체가 같은 상한(_limit) 안에서. 예외가 나면 되돌린다."""
    p = await pool()
    async with _limit(), p.connection() as conn, conn.transaction(), conn.cursor() as cur:
        yield cur
