"""통합 테스트 환경: roadrail_test DB 에 db/migrations 를 새로 적용하고, Redis 는 15번 DB 를 쓴다.

    TEST_DATABASE_URL (기본 postgresql://roadrail:roadrail@localhost:5462/roadrail_test)
    TEST_REDIS_URL    (기본 redis://localhost:6409/15)
접속이 안 되면 통합 테스트는 건너뛴다 (단위·계약 테스트만 실행).
"""
from __future__ import annotations

import os
from pathlib import Path

import psycopg
import pytest
import redis

MIGRATIONS = Path(__file__).resolve().parents[3] / "db" / "migrations"
DB_URL = os.environ.get("TEST_DATABASE_URL", "postgresql://roadrail:roadrail@localhost:5462/roadrail_test")
REDIS_URL = os.environ.get("TEST_REDIS_URL", "redis://localhost:6409/15")


def _available() -> bool:
    try:
        psycopg.connect(DB_URL, connect_timeout=2).close()
        redis.Redis.from_url(REDIS_URL, socket_connect_timeout=2).ping()
        return True
    except Exception:  # noqa: BLE001
        return False


def pytest_collection_modifyitems(config, items):
    if _available():
        return
    skip = pytest.mark.skip(reason="PostgreSQL/Redis 테스트 인스턴스 없음 (make up 후 make test)")
    for item in items:
        if "integration" in str(item.fspath):
            item.add_marker(skip)


def apply_migrations() -> None:
    with psycopg.connect(DB_URL, autocommit=True) as conn:
        conn.execute("DROP SCHEMA IF EXISTS ref, ts, rail, env, ana, ops CASCADE")
        for f in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
            conn.execute(f.read_text())


@pytest.fixture(scope="session", autouse=True)
def _env():
    if not _available():
        yield
        return
    os.environ["DATABASE_URL"] = DB_URL
    os.environ["REDIS_URL"] = REDIS_URL
    from roadrail.core.config import settings
    settings.cache_clear()
    apply_migrations()
    yield


@pytest.fixture(autouse=True)
async def _clean():
    from roadrail.core import db, rds
    if _available():
        redis.Redis.from_url(REDIS_URL).flushdb()
    yield
    await db.close()
    await rds.close()
