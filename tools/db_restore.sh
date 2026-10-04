#!/usr/bin/env bash
# 백업 복원.
#   확인(리허설)  make restore-check FILE=backups/….dump
#       별도 DB(roadrail_restore)에 복원하고, 표마다 행 수를 지금 DB 와 나란히 보인다. 지금 DB 는 건드리지 않는다.
#   교체          make restore FILE=backups/….dump
#       확인 질문 → 지금 DB 를 자동 백업(backups/before-restore) → 별도 DB 에 복원 → api · collector 를 멈추고 이름을 바꿔 끼운 뒤 다시 띄운다.
#       멈추는 시간은 이름 바꾸기 동안뿐이고, 실패하면 원래 DB 가 그대로 남는다.
set -euo pipefail
cd "$(dirname "$0")/.."

FILE=${1:?"사용법: tools/db_restore.sh <백업 파일> [--replace]"}
MODE=${2:-}
LIVE=${DB_NAME:-roadrail}
STAGE="${LIVE}_restore"
[ -f "$FILE" ] || { echo "백업 파일이 없습니다: $FILE" >&2; exit 1; }

if [ "$MODE" = "--replace" ] && [ "${CONFIRM:-}" != "$LIVE" ]; then   # 교체는 무거운 복원 전에 먼저 묻는다
  [ -t 0 ] || { echo "교체하려면 CONFIRM=$LIVE 를 함께 주세요 (대화형이 아님)" >&2; exit 1; }
  read -r -p "지금 DB '$LIVE' 를 이 백업으로 바꿉니다. 계속하려면 $LIVE 를 입력하세요: " answer
  [ "$answer" = "$LIVE" ] || { echo "취소했습니다." >&2; exit 1; }
fi

sql() { docker compose exec -T db psql -U roadrail -v ON_ERROR_STOP=1 -X -At "$@"; }
kick() { sql -d postgres -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname = '$1' AND pid <> pg_backend_pid()" >/dev/null; }

# 표마다 행 수 (분할 표는 부모에서 한 번에) — "스키마.표|행 수", 이름순
counts() {
  sql -d "$1" -c "
    SELECT format('%I.%I', n.nspname, c.relname) || '|' ||
           (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition AND n.nspname IN ('ref', 'ts', 'rail', 'env', 'ana', 'ops')
    ORDER BY 1"
}

echo "복원: $FILE → $STAGE"
kick "$STAGE"
sql -d postgres -c "SET client_min_messages = warning" -c "DROP DATABASE IF EXISTS \"$STAGE\"" >/dev/null
sql -d postgres -c "CREATE DATABASE \"$STAGE\" OWNER roadrail" >/dev/null
t0=$(date +%s)
docker compose exec -T db pg_restore -U roadrail -d "$STAGE" --no-owner --exit-on-error < "$FILE"
echo "복원 끝 ($(( $(date +%s) - t0 ))초)"

tmpdir=$(mktemp -d)
stopped=false   # 교체 도중 실패해도 멈춘 서비스는 다시 띄운다
trap 'rm -rf "$tmpdir"; if $stopped; then docker compose start api collector; fi' EXIT
counts "$STAGE" | LC_ALL=C sort > "$tmpdir/restored"
{ counts "$LIVE" 2>/dev/null || true; } | LC_ALL=C sort > "$tmpdir/live"
[ -s "$tmpdir/restored" ] || { echo "복원한 DB 에 표가 없습니다" >&2; exit 1; }
printf '%-34s %14s %14s\n' "표" "백업(복원)" "지금 $LIVE"
LC_ALL=C join -t '|' -a 1 -a 2 -e '-' -o 0,1.2,2.2 "$tmpdir/restored" "$tmpdir/live" |
  awk -F '|' '{ printf "%-34s %14s %14s%s\n", $1, $2, $3, ($2 != $3 ? "  *" : "") }'
echo "표 $(wc -l < "$tmpdir/restored" | tr -d ' ')개 · 행 $(awk -F '|' '{ s += $2 } END { print s }' "$tmpdir/restored")개 (* = 지금 DB 와 다름 — 백업 뒤에 쌓인 수집분이면 정상)"

if [ "$MODE" != "--replace" ]; then
  echo "확인만 했습니다. 지금 DB($LIVE)는 그대로입니다. 교체: make restore FILE=$FILE · 정리: DROP DATABASE $STAGE"
  exit 0
fi

# ---- 교체
BACKUP_DIR=backups/before-restore KEEP=3 DB_NAME="$LIVE" tools/db_backup.sh

live_services=false   # 실제 DB 일 때만 그 DB 를 쓰는 서비스를 멈췄다 띄운다 (DB_NAME 으로 다른 DB 를 시험할 때는 그대로)
[ "$LIVE" = roadrail ] && live_services=true
if $live_services; then docker compose stop api collector; stopped=true; fi
old="${LIVE}_old_$(date +%Y%m%d%H%M%S)"
kick "$LIVE"
sql -d postgres -c "ALTER DATABASE \"$LIVE\" RENAME TO \"$old\"" >/dev/null
sql -d postgres -c "ALTER DATABASE \"$STAGE\" RENAME TO \"$LIVE\"" >/dev/null
if $live_services; then docker compose start api collector; stopped=false; fi
sql -d postgres -c "DROP DATABASE \"$old\"" >/dev/null
echo "교체 끝: $LIVE ← $FILE (교체 전 DB 는 backups/before-restore 에 백업)"
