#!/bin/sh
# 개발 DB(roadrail) → pg_dump(custom) → QA DB 로 복원. 백업 · 복원 가능성 확인을 겸한다.
#   덤프 파일은 저장소 밖(QA_DUMP, 기본 /tmp/roadrail-qa.dump)에 둔다 — 실데이터를 커밋하지 않는다.
set -eu
cd "$(dirname "$0")/.."
DUMP="${QA_DUMP:-/tmp/roadrail-qa.dump}"
docker compose exec -T db pg_dump -U roadrail -d roadrail -Fc > "$DUMP"
echo "dump: $(du -h "$DUMP" | cut -f1)"
docker compose -f qa/compose.qa.yml up -d db redis
until docker compose -f qa/compose.qa.yml exec -T db pg_isready -U roadrail -d roadrail >/dev/null 2>&1; do sleep 1; done
docker compose -f qa/compose.qa.yml exec -T db psql -U roadrail -d postgres -c "DROP DATABASE IF EXISTS roadrail WITH (FORCE)" -c "CREATE DATABASE roadrail"
docker compose -f qa/compose.qa.yml exec -T db pg_restore -U roadrail -d roadrail --no-owner --exit-on-error < "$DUMP"
docker compose -f qa/compose.qa.yml exec -T db psql -U roadrail -d roadrail -Atc \
  "SELECT 'run_info', count(*) FROM rail.run_info UNION ALL SELECT 'road_corridor_tt', count(*) FROM ts.road_corridor_tt UNION ALL SELECT 'flyway', max(version::int) FROM ops.flyway_schema_history"
