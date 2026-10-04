#!/usr/bin/env bash
# DB 백업 — pg_dump 사용자 지정 형식(-Fc, 압축 · 표 단위 복원 가능). make backup
#   backups/roadrail-YYYYmmdd-HHMMSS.dump 를 만들고, 읽을 수 있는지(목차 · 표 데이터 수) 확인한 뒤에만 이름을 붙인다.
#   오래된 백업은 KEEP 개(기본 7)만 남긴다. 백업에는 수집한 데이터가 그대로 있다(키는 없음 — 호출 기록은 마스킹) → 저장소 밖(.gitignore)
set -euo pipefail
cd "$(dirname "$0")/.."

DIR=${BACKUP_DIR:-backups}
KEEP=${KEEP:-7}
DB=${DB_NAME:-roadrail}
mkdir -p "$DIR"
out="$DIR/$DB-$(date +%Y%m%d-%H%M%S).dump"
tmp="$out.part"
trap 'rm -f "$tmp"' EXIT

docker compose exec -T db pg_dump -U roadrail -d "$DB" -Fc > "$tmp"
tables=$(docker compose exec -T db pg_restore --list < "$tmp" | grep -c " TABLE DATA " || true)
if [ "${tables:-0}" -eq 0 ]; then
  echo "백업 확인 실패 — 목차에 표 데이터가 없습니다 ($tmp)" >&2
  exit 1
fi
mv "$tmp" "$out"
echo "백업 $out ($(du -h "$out" | cut -f1) · 표 데이터 $tables개)"

# 보관 개수 — 가장 최근 KEEP 개만
ls -1t "$DIR/$DB"-*.dump 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
  rm -f -- "$old"
  echo "지움 $old (보관 $KEEP개)"
done
