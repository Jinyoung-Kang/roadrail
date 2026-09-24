"""Redis 연결 (예산 카운터 · 작업 잠금 · 명령 스트림 · heartbeat)."""
from __future__ import annotations

import redis.asyncio as aioredis

from .config import settings

_client: aioredis.Redis | None = None

# 키 규약 (Java api 와 공유 — docs/redis-keys.md)
HEARTBEAT = "rr:collector:heartbeat"
COMMANDS = "rr:commands"
COMMAND_GROUP = "collector"


def lock_key(job: str) -> str:
    return f"rr:lock:{job}"


def quota_key(provider: str, yyyymmdd: str) -> str:
    return f"quota:{provider}:{yyyymmdd}"


def tail_key(start: str, end: str, yyyymmdd: str) -> str:
    return f"ex:tail:{start}-{end}:{yyyymmdd}"


def client() -> aioredis.Redis:
    global _client
    if _client is None:
        _client = aioredis.from_url(settings().redis_url, decode_responses=True)
    return _client


async def close() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None
