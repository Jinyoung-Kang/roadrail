"""PostgreSQL (psycopg 3 async pool)."""
from __future__ import annotations

from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from .config import settings

_pool: AsyncConnectionPool | None = None


async def pool() -> AsyncConnectionPool:
    global _pool
    if _pool is None:
        _pool = AsyncConnectionPool(
            settings().database_url, min_size=1, max_size=8, open=False,
            kwargs={"row_factory": dict_row, "options": "-c timezone=Asia/Seoul"},
        )
        await _pool.open(wait=True, timeout=30)
    return _pool


async def close() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None


async def fetch(sql: str, params=None) -> list[dict]:
    p = await pool()
    async with p.connection() as conn, conn.cursor() as cur:
        await cur.execute(sql, params)
        return await cur.fetchall() if cur.description else []


async def fetchone(sql: str, params=None) -> dict | None:
    rows = await fetch(sql, params)
    return rows[0] if rows else None


async def execute(sql: str, params=None) -> int:
    p = await pool()
    async with p.connection() as conn, conn.cursor() as cur:
        await cur.execute(sql, params)
        return cur.rowcount


async def executemany(sql: str, rows: list) -> int:
    if not rows:
        return 0
    p = await pool()
    async with p.connection() as conn, conn.cursor() as cur:
        await cur.executemany(sql, rows)
        return len(rows)
