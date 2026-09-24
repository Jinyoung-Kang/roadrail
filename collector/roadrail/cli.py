"""CLI — make 명령에서 호출 (docker compose exec collector roadrail …).

  roadrail serve                       상주 수집기 (컨테이너 기본 명령)
  roadrail seed                        seed/corridors.yaml 적용 (멱등, 변경 건수 출력)
  roadrail run <job>                   작업 1회 실행 (collect-once)
  roadrail rail-backfill FROM TO       운행정보 기간 재수집 (YYYY-MM-DD)
  roadrail recompute-rail FROM TO      저장된 운행정보로 정시성 재계산 (API 호출 없음)
  roadrail reclassify-road             도로 품질 규칙 재적용 + 길 합산 재계산 (API 호출 없음)
  roadrail rail-geometry [FILE]        역 쌍 선로 경로 다시 계산 (FILE: 미리 받은 Overpass JSON — 호출 없음)
"""
from __future__ import annotations

import asyncio
import datetime as dt
import sys

from .core import db, rds
from .core.log import setup_logging


async def _run(argv: list[str]) -> int:
    from .pipeline import rail
    from .pipeline.seed import apply_seed
    from .scheduler.jobs import JOBS, new_id, run_backfill, run_job

    cmd = argv[0] if argv else "help"
    try:
        if cmd == "seed":
            print(f"seed 적용: 변경 {await apply_seed()}건")
        elif cmd == "run" and len(argv) == 2 and argv[1] in JOBS:
            print(await run_job(argv[1], "ADMIN"))
        elif cmd == "rail-backfill" and len(argv) == 3:
            start, end = dt.date.fromisoformat(argv[1]), dt.date.fromisoformat(argv[2])
            bid = new_id()
            planned = ((end - start).days + 1) * rail.CALLS_PER_DAY
            await db.execute("""INSERT INTO ops.backfill (backfill_id, provider, job_name, from_date, to_date, planned_calls)
                                VALUES (%s, 'KORAIL', 'rail_daily', %s, %s, %s)""", (bid, start, end, planned))
            print(await run_backfill(bid))
        elif cmd == "rail-geometry" and len(argv) in (1, 2):
            print(await run_job("rail_geometry", "ADMIN", **({"source_file": argv[1]} if len(argv) == 2 else {})))
        elif cmd == "reclassify-road":
            from .pipeline.road import reclassify_all
            changed, slots = await reclassify_all()
            print(f"품질 변경 {changed}행 · 길 슬롯 {slots}개 재계산")
        elif cmd == "recompute-rail" and len(argv) == 3:
            d, end = dt.date.fromisoformat(argv[1]), dt.date.fromisoformat(argv[2])
            while d <= end:
                print(d, await rail.compute_day(d))
                d += dt.timedelta(days=1)
        else:
            print(__doc__)
            print("작업:", ", ".join(JOBS))
            return 2
        return 0
    finally:
        await db.close()
        await rds.close()


def main() -> None:
    argv = sys.argv[1:]
    if argv and argv[0] == "serve":
        from .scheduler.main import run
        run()
        return
    setup_logging()
    sys.exit(asyncio.run(_run(argv)))
