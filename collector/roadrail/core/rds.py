"""Redis 연결 (예산 카운터 · 작업 잠금 · 명령 스트림 · heartbeat)."""
from __future__ import annotations

import redis.asyncio as aioredis

from .config import settings

_client: aioredis.Redis | None = None

# 키 규약 (Java api 와 공유 — docs/redis-keys.md)
HEARTBEAT = "rr:collector:heartbeat"
COMMANDS = "rr:commands"
COMMAND_GROUP = "collector"

# 명령 스트림을 기다리는 시간(XREADGROUP BLOCK)과 소켓 읽기 제한 — 제한은 반드시 블록보다 길어야 한다.
# redis-py 8 의 기본 socket_timeout(5초)이 블록(5초)과 같아 빈 스트림 대기가 TimeoutError · 재시도로 끊겼다(실측).
# 끊긴 연결로 배달된 명령은 재기동 전까지 pending 에 남을 수 있다. 다른 명령은 ms 단위라 15초는 죽은 연결만 걸러낸다.
COMMAND_BLOCK_MS = 5000
SOCKET_TIMEOUT_S = 15
# 연결 풀 상한 — redis-py 8 부터 기본 상한 100 을 넘으면 MaxConnectionsError 로 바로 실패한다(도로 통행시간 작업이
# 구간 100여 개의 꼬리 위치를 동시에 읽다 실패, 실측). 상한은 두되 넘치면 빈 연결을 기다리는 풀(기본 최대 20초)을 쓴다.
MAX_CONNECTIONS = 100


def lock_key(job: str) -> str:
    return f"rr:lock:{job}"


def quota_key(provider: str, yyyymmdd: str) -> str:
    return f"quota:{provider}:{yyyymmdd}"


def tail_key(start: str, end: str) -> str:
    """구간의 꼬리 커서 — 값은 '원천 데이터 날짜:그날 본 1종 행 수' (날짜를 키가 아니라 값에 둔다, H1)"""
    return f"ex:tail:{start}-{end}"


def client() -> aioredis.Redis:
    global _client
    if _client is None:
        pool = aioredis.BlockingConnectionPool.from_url(settings().redis_url, decode_responses=True,
                                                         socket_timeout=SOCKET_TIMEOUT_S, max_connections=MAX_CONNECTIONS)
        _client = aioredis.Redis.from_pool(pool)  # aclose() 가 풀까지 닫는다
    return _client


async def close() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None
