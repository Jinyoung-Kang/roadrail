"""외부 호출 공통 계층: 예산 차감 · 동시성 제한 · 재시도 · 호출 기록(키 마스킹) (FR-206)."""
from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field

import httpx

from ..core.log import log, mask_params, mask_text
from ..scheduler.quota import Allowance, QuotaBudget

logger = logging.getLogger(__name__)

CONCURRENCY = {"EX": 4, "KORAIL": 2, "KMA": 4, "AIRKOREA": 2, "KAKAO": 4, "KAKAO_LOCAL": 4}
TIMEOUT = {"EX": 15.0, "KORAIL": 60.0, "KMA": 10.0, "AIRKOREA": 10.0, "KAKAO": 10.0, "KAKAO_LOCAL": 10.0}
_semaphores: dict[str, asyncio.Semaphore] = {}


class ProviderError(Exception):
    def __init__(self, provider: str, endpoint: str, message: str, status: int | None = None):
        super().__init__(f"{provider}/{endpoint}: {message}")
        self.provider, self.endpoint, self.status = provider, endpoint, status


def _sem(provider: str) -> asyncio.Semaphore:
    if provider not in _semaphores:
        _semaphores[provider] = asyncio.Semaphore(CONCURRENCY.get(provider, 2))
    return _semaphores[provider]


def check_payload(provider: str, body: dict) -> tuple[str | None, str | None]:
    """공급자별 '200 이지만 실패' 응답 판별 → (result_code, error)."""
    if provider == "EX":
        code = body.get("code")
        return code, None if code in (None, "SUCCESS") else body.get("message") or code
    if provider in ("KORAIL", "KMA", "AIRKOREA"):
        if "OpenAPI_ServiceResponse" in body:  # data.go.kr 게이트웨이 오류
            h = body["OpenAPI_ServiceResponse"].get("cmmMsgHeader", {})
            return h.get("returnReasonCode"), h.get("returnAuthMsg") or h.get("errMsg")
        header = (body.get("response") or {}).get("header") or {}
        code = header.get("resultCode")
        ok = code in ("0", "00", "03")  # 03 = NO_DATA (정상, 결과 없음)
        return code, None if ok else header.get("resultMsg") or f"resultCode={code}"
    return None, None


@dataclass
class JobContext:
    """작업 1회 실행의 호출 문맥. 공급자별 예산 몫 · 호출 기록 버퍼를 가진다."""
    job_name: str
    http: httpx.AsyncClient
    budget: QuotaBudget | None
    estimates: dict[str, int] = field(default_factory=dict)
    trigger: str = "SCHEDULE"
    calls: int = 0
    rows: int = 0
    api_calls: list[tuple] = field(default_factory=list)
    allowances: dict[str, Allowance] = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)

    async def open_budgets(self) -> None:
        """작업 시작 시 예상 호출 수 예약. 부족하면 QuotaExhausted (→ SKIPPED_QUOTA)."""
        if self.budget is None:
            return
        for provider, est in self.estimates.items():
            self.allowances[provider] = await self.budget.allowance(provider, est)

    async def close_budgets(self) -> None:
        for a in self.allowances.values():
            await a.close()

    async def _take(self, provider: str) -> None:
        if self.budget is None:
            return
        if provider not in self.allowances:
            self.allowances[provider] = await self.budget.allowance(provider, 0)
        await self.allowances[provider].take()

    async def get_json(self, provider: str, endpoint: str, url: str, params: dict,
                       headers: dict | None = None, retries: int = 2) -> dict:
        await self._take(provider)
        attempt, last_err = 0, None
        async with _sem(provider):
            while attempt <= retries:
                if attempt > 0:  # 재시도도 실제 호출이므로 예산에서 차감
                    await self._take(provider)
                t0 = time.perf_counter()
                status, code, err, rows = None, None, None, None
                try:
                    resp = await self.http.get(url, params=params, headers=headers or {},
                                               timeout=TIMEOUT.get(provider, 15.0))
                    status = resp.status_code
                    text = resp.text
                    if status >= 500:
                        raise ProviderError(provider, endpoint, f"HTTP {status}", status)
                    try:
                        body = json.loads(text)
                    except ValueError as e:
                        raise ProviderError(provider, endpoint, "JSON 아님: " + mask_text(text[:200]), status) from e
                    if status != 200:
                        raise ProviderError(provider, endpoint, f"HTTP {status}: " + mask_text(text[:200]), status)
                    code, err = check_payload(provider, body)
                    if err:
                        raise ProviderError(provider, endpoint, str(err), status)
                    return body
                except (httpx.TransportError, ProviderError) as e:
                    last_err = e
                    err = mask_text(str(e))[:500]
                    retryable = isinstance(e, httpx.TransportError) or (status is not None and status >= 500)
                    if not retryable or attempt == retries:
                        raise ProviderError(provider, endpoint, err, status) from e
                    await asyncio.sleep(0.8 * (attempt + 1))
                finally:
                    self.calls += 1
                    self.api_calls.append((
                        self.job_name, provider, endpoint, json.dumps(mask_params(params), ensure_ascii=False),
                        status, code, int((time.perf_counter() - t0) * 1000), rows, err,
                    ))
                    attempt += 1
        raise ProviderError(provider, endpoint, str(last_err))

    def note(self, msg: str, **fields) -> None:
        self.notes.append(msg)
        log(logger, msg, job=self.job_name, **fields)
