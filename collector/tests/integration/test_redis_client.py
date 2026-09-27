"""Redis 클라이언트 설정 (redis-py 8 기본값 변화에 대한 회귀):
소켓 읽기 제한 5초 = 명령 스트림 블록 5초, 연결 풀 상한 100 초과 시 즉시 실패."""
import asyncio

from roadrail.core import rds

STREAM = "rr:test:commands"


async def test_command_block_read_outlasts_socket_timeout():
    r = rds.client()
    await r.xgroup_create(STREAM, "g", id="$", mkstream=True)
    try:
        # 빈 스트림에서 블록 시간만큼 기다린 뒤 빈 결과 — 수정 전(redis-py 8 기본값)에는 TimeoutError
        resp = await r.xreadgroup("g", "c", {STREAM: ">"}, count=1, block=rds.COMMAND_BLOCK_MS)
        assert not resp
    finally:
        await r.delete(STREAM)


async def test_burst_beyond_pool_limit_waits_instead_of_failing():
    # 도로 통행시간 작업은 구간 100여 개의 꼬리 위치를 동시에 읽는다 — 수정 전에는 MaxConnectionsError
    n = rds.MAX_CONNECTIONS + 50
    got = await asyncio.gather(*(rds.client().get(f"rr:test:burst:{i}") for i in range(n)))
    assert got == [None] * n
