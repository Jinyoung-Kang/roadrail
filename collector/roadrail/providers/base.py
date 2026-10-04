"""외부 호출 공통 계층: 예산 차감 · 동시성 제한 · 재시도 · 호출 기록(키 마스킹) (FR-206)."""
from __future__ import annotations

import asyncio
import json
import logging
import time
import xml.etree.ElementTree as ET
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import httpx

from ..core.log import log, mask_params, mask_text
from ..core.timeutil import now_kst
from ..scheduler.quota import Allowance, QuotaBudget

logger = logging.getLogger(__name__)

CONCURRENCY = {"EX": 4, "KORAIL": 2, "KMA": 4, "AIRKOREA": 2, "KAKAO": 4, "KAKAO_LOCAL": 4, "OSM": 1, "KASI": 1, "UTIC": 1}
TIMEOUT = {"EX": 15.0, "KORAIL": 60.0, "KMA": 10.0, "AIRKOREA": 25.0, "KAKAO": 10.0, "KAKAO_LOCAL": 10.0, "OSM": 240.0, "KASI": 10.0,
           "UTIC": 20.0}
# XML 응답 상한 — UTIC 전국 돌발 목록은 약 140KB. 비정상적으로 큰 응답은 파싱 전에 거른다
MAX_XML_CHARS = 5_000_000
# 응답 바이트 상한 — 받는 도중에 끊는다(L5). 선로 형상(Overpass)만 크다(구역 하나 수십 MB)
MAX_BYTES = {"OSM": 256_000_000}
DEFAULT_MAX_BYTES = 16_000_000
# 요청 하나의 전체 제한 시간 = 단계별 제한(TIMEOUT)의 배수 — 조금씩 흘러오는 응답이 작업 잠금 시간을 넘기지 않게
TOTAL_TIMEOUT_FACTOR = 3
_semaphores: dict[str, asyncio.Semaphore] = {}


class ProviderError(Exception):
    def __init__(self, provider: str, endpoint: str, message: str, status: int | None = None, code: str | None = None):
        super().__init__(f"{provider}/{endpoint}: {message}")
        self.provider, self.endpoint, self.status, self.detail, self.code = provider, endpoint, status, message, code


def _sem(provider: str) -> asyncio.Semaphore:
    if provider not in _semaphores:
        _semaphores[provider] = asyncio.Semaphore(CONCURRENCY.get(provider, 2))
    return _semaphores[provider]


def check_payload(provider: str, body: dict) -> tuple[str | None, str | None]:
    """공급자별 '200 이지만 실패' 응답 판별 → (result_code, error)."""
    if provider == "EX":
        code = body.get("code")
        return code, None if code in (None, "SUCCESS") else body.get("message") or code
    if provider in ("KORAIL", "KMA", "AIRKOREA", "KASI"):
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
        def parse(text: str, status: int) -> tuple[dict, str | None]:
            try:
                body = json.loads(text)
            except ValueError as e:
                raise ProviderError(provider, endpoint, "JSON 아님: " + mask_text(text[:200]), status) from e
            if status != 200:
                raise ProviderError(provider, endpoint, f"HTTP {status}: " + mask_text(text[:200]), status)
            code, err = check_payload(provider, body)
            if err:
                raise ProviderError(provider, endpoint, str(err), status, code)
            return body, code
        return await self._get(provider, endpoint, url, params, headers, retries, parse)

    async def get_xml(self, provider: str, endpoint: str, url: str, params: dict,
                      headers: dict | None = None, retries: int = 2) -> ET.Element:
        """XML 응답(경찰청 UTIC). 표준 라이브러리 expat 은 외부 엔티티를 따라가지 않고, 엔티티 폭주는 expat ≥ 2.4 가 막는다."""
        def parse(text: str, status: int) -> tuple[ET.Element, str | None]:
            if status != 200:
                raise ProviderError(provider, endpoint, f"HTTP {status}: " + mask_text(text[:200]), status)
            if len(text) > MAX_XML_CHARS:
                raise ProviderError(provider, endpoint, f"응답이 너무 큼 ({len(text):,}자)", status)
            if text.lstrip()[:1] in ("[", "{"):
                # 키 오류 등은 HTTP 200 + JSON 으로 온다 (실측: [{"resultCode":"02","resultMsg":"유효한 KEY값이 아닙니다."}])
                try:
                    j = json.loads(text)
                except ValueError:
                    j = None
                e = j[0] if isinstance(j, list) and j and isinstance(j[0], dict) else j if isinstance(j, dict) else {}
                if e.get("resultCode") is not None:
                    code = str(e["resultCode"])[:20]
                    raise ProviderError(provider, endpoint,
                                        mask_text(f"오류 응답 {code}: {str(e.get('resultMsg') or '')[:200]}"), status, code)
            try:
                return ET.fromstring(text), None
            except ET.ParseError as e:
                raise ProviderError(provider, endpoint, "XML 아님: " + mask_text(text[:200]), status) from e
        return await self._get(provider, endpoint, url, params, headers, retries, parse)

    async def _get(self, provider: str, endpoint: str, url: str, params: dict, headers: dict | None, retries: int,
                   parse: Callable[[str, int], tuple[Any, str | None]]) -> Any:
        """예산 차감 · 동시성 제한 · 5xx/전송 오류 재시도 · 호출 기록(키 마스킹). parse 가 본문을 해석하고 실패면 ProviderError."""
        await self._take(provider)
        attempt, last_err = 0, None
        async with _sem(provider):
            while attempt <= retries:
                if attempt > 0:  # 재시도도 실제 호출이므로 예산에서 차감
                    await self._take(provider)
                t0 = time.perf_counter()
                called_at = now_kst()  # 호출 기록은 작업 끝에 한꺼번에 넣으므로 시각은 여기서 잡는다(L2)
                status, code, err, rows = None, None, None, None
                try:
                    status, text = await self._fetch_text(provider, endpoint, url, params, headers)
                    if status >= 500:
                        raise ProviderError(provider, endpoint, f"HTTP {status}", status)
                    if 300 <= status < 400:  # 키가 쿼리에 있어 따라가지 않는다(L6)
                        raise ProviderError(provider, endpoint, f"리다이렉트 응답 HTTP {status} — 따라가지 않음", status)
                    body, code = parse(text, status)
                    return body
                except (httpx.TransportError, ProviderError, TimeoutError) as e:
                    last_err = e
                    if isinstance(e, ProviderError) and e.code is not None:
                        code = e.code
                    # httpx 시간 초과 등은 str(e) 가 빈 문자열 → 예외 이름을 남긴다 (오류 상세에서 원인이 보이게)
                    err = mask_text(e.detail if isinstance(e, ProviderError) else f"{type(e).__name__}: {e}".rstrip(": "))[:500]
                    retryable = isinstance(e, httpx.TransportError | TimeoutError) or (status is not None and status >= 500)
                    if not retryable or attempt == retries:
                        raise ProviderError(provider, endpoint, err, status, code) from e
                    await asyncio.sleep(0.8 * (attempt + 1))
                finally:
                    self.calls += 1
                    self.api_calls.append((
                        self.job_name, provider, endpoint, json.dumps(mask_params(params), ensure_ascii=False),
                        status, code, int((time.perf_counter() - t0) * 1000), rows, err, called_at,
                    ))
                    attempt += 1
        raise ProviderError(provider, endpoint, str(last_err))

    async def _fetch_text(self, provider: str, endpoint: str, url: str, params: dict,
                          headers: dict | None) -> tuple[int, str]:
        """본문을 바이트 상한까지만 받는다 — 넘으면 받는 도중에 끊고, 전체 시간도 제한한다(L5)."""
        step = TIMEOUT.get(provider, 15.0)
        limit = MAX_BYTES.get(provider, DEFAULT_MAX_BYTES)
        async with asyncio.timeout(step * TOTAL_TIMEOUT_FACTOR):
            async with self.http.stream("GET", url, params=params, headers=headers or {}, timeout=step) as resp:
                chunks, size = [], 0
                async for chunk in resp.aiter_bytes():
                    size += len(chunk)
                    if size > limit:
                        raise ProviderError(provider, endpoint, f"응답이 너무 큼 (>{limit:,}바이트)", resp.status_code)
                    chunks.append(chunk)
                return resp.status_code, b"".join(chunks).decode(resp.encoding or "utf-8", errors="replace")

    def note(self, msg: str, **fields) -> None:
        self.notes.append(msg)
        log(logger, msg, job=self.job_name, **fields)
