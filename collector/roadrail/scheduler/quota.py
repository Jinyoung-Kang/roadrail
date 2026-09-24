"""QuotaBudget — 공급자별 일일 호출 예산 (FR-202, NFR-02, ADR-002).

Redis 키 (KST 날짜 기준, 48시간 TTL)
  quota:{P}:{yyyymmdd}       예약 누계 (소비 + 아직 쓰지 않은 예약). 한도 비교 대상
  quota:used:{P}:{yyyymmdd}  실제 호출 수

예약은 Lua 스크립트 한 번으로 "읽기-비교-증가" 를 원자적으로 수행하므로
동시 작업이 여럿이어도 한도를 넘지 않는다 (tests/integration/test_quota.py).
작업은 시작할 때 예상 호출 수를 예약하고(부족하면 SKIPPED_QUOTA), 호출마다 차감하며,
예상보다 더 필요하면 1건씩 추가 예약하고, 끝나면 남은 예약을 환불한다.
"""
from __future__ import annotations

import datetime as dt
from collections.abc import Callable

from ..core.timeutil import now_kst

RESERVE_LUA = """
local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
local n = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
if cur + n > limit then return -1 end
local v = redis.call('INCRBY', KEYS[1], n)
redis.call('EXPIRE', KEYS[1], 172800)
return v
"""

REFUND_LUA = """
local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
local n = math.min(tonumber(ARGV[1]), cur)
if n > 0 then redis.call('DECRBY', KEYS[1], n) end
return cur - n
"""


class QuotaExhausted(Exception):
    def __init__(self, provider: str, needed: int, remaining: int):
        super().__init__(f"{provider} 일일 예산 부족 (필요 {needed}, 남음 {remaining})")
        self.provider, self.needed, self.remaining = provider, needed, remaining


class QuotaBudget:
    def __init__(self, redis, limit_of: Callable[[str], int], clock: Callable[[], dt.datetime] = now_kst):
        self.r = redis
        self.limit_of = limit_of
        self.clock = clock
        self._reserve = redis.register_script(RESERVE_LUA)
        self._refund = redis.register_script(REFUND_LUA)

    def _day(self) -> str:
        return self.clock().strftime("%Y%m%d")

    def _k(self, provider: str) -> str:
        return f"quota:{provider}:{self._day()}"

    def _ku(self, provider: str) -> str:
        return f"quota:used:{provider}:{self._day()}"

    async def reserve(self, provider: str, n: int) -> bool:
        if n <= 0:
            return True
        v = await self._reserve(keys=[self._k(provider)], args=[n, self.limit_of(provider)])
        return int(v) >= 0

    async def refund(self, provider: str, n: int) -> None:
        if n > 0:
            await self._refund(keys=[self._k(provider)], args=[n])

    async def consume(self, provider: str, n: int = 1) -> None:
        """이미 예약된 몫에서 실제 호출 n 건을 기록."""
        key = self._ku(provider)
        await self.r.incrby(key, n)
        await self.r.expire(key, 172800)

    async def snapshot(self, provider: str) -> dict:
        reserved_total = int(await self.r.get(self._k(provider)) or 0)
        used = int(await self.r.get(self._ku(provider)) or 0)
        limit = self.limit_of(provider)
        return {"provider": provider, "day": self.clock().date().isoformat(), "limit": limit,
                "used": used, "reserved": max(reserved_total - used, 0), "remaining": max(limit - reserved_total, 0)}

    async def allowance(self, provider: str, estimate: int) -> Allowance:
        if not await self.reserve(provider, estimate):
            snap = await self.snapshot(provider)
            raise QuotaExhausted(provider, estimate, snap["remaining"])
        return Allowance(self, provider, estimate)


class Allowance:
    """작업 하나가 가진 예약분. take() 로 1건씩 차감, close() 로 남은 몫 환불."""

    def __init__(self, budget: QuotaBudget, provider: str, reserved: int):
        self.budget, self.provider = budget, provider
        self.left = reserved
        self.used = 0

    async def take(self) -> None:
        if self.left <= 0:
            if not await self.budget.reserve(self.provider, 1):
                snap = await self.budget.snapshot(self.provider)
                raise QuotaExhausted(self.provider, 1, snap["remaining"])
            self.left += 1
        self.left -= 1
        self.used += 1
        await self.budget.consume(self.provider)

    async def close(self) -> None:
        await self.budget.refund(self.provider, self.left)
        self.left = 0
