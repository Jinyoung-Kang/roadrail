"""공휴일 달력 (holiday_sync 작업) — 작년 · 올해 · 내년을 ref.holiday 에 (멱등)."""
from __future__ import annotations

import datetime as dt

from ..core import db
from ..core.timeutil import now_kst
from ..providers import kasi
from ..providers.base import JobContext, ProviderError


async def sync_holidays(ctx: JobContext) -> int:
    year = now_kst().year
    rows: list[dict] = []
    for y in (year - 1, year, year + 1):
        try:
            rows += kasi.parse_rest_days(await kasi.rest_days(ctx, y))
        except ProviderError as e:
            ctx.note(f"PARTIAL: {y}년 {e}")
    await db.executemany("""
        INSERT INTO ref.holiday (day, name, kind, fetched_at) VALUES (%s, %s, %s, now())
        ON CONFLICT (day) DO UPDATE SET name = EXCLUDED.name, kind = EXCLUDED.kind, fetched_at = now()""",
        [(r["day"], r["name"], r["kind"]) for r in rows])
    ctx.rows += len(rows)
    ctx.note(f"공휴일 {len(rows)}일 ({year - 1}~{year + 1})")
    return len(rows)


async def holiday_days(since: dt.date) -> frozenset[dt.date]:
    return frozenset(r["day"] for r in await db.fetch("SELECT day FROM ref.holiday WHERE day >= %s", (since,)))
