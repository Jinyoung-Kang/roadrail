# Redis 키 규약 (collector · api 공유)

| 키 | 형식 | 쓰는 쪽 | 읽는 쪽 | TTL |
|---|---|---|---|---|
| `quota:{P}:{yyyymmdd}` | 정수 — 예약 누계(소비 + 미사용 예약) | collector (Lua 원자 예약·환불) | api (남은 예산 → 429) | 48h |
| `quota:used:{P}:{yyyymmdd}` | 정수 — 실제 호출 수 | collector | api (수집 상태) | 48h |
| `rr:lock:{job}` | 실행 토큰 | collector (SET NX EX) | api (→ 409 JOB_RUNNING) | 작업별 (15분~2시간) |
| `rr:commands` | Stream `{type: run_job \| backfill, …}` | api (XADD) | collector (consumer group `collector`, 처리 후 XACK) | — |
| `rr:collector:heartbeat` | ISO 시각 | collector (30초마다) | api `/health` | 90s |
| `ex:tail:{start}-{end}:{yyyymmdd}` | 정수 — 오늘 본 1종 행 수 (꼬리 페이지 커서) | collector | — | 48h |
| `now:{id}:{dir}:{departIn}:{access}:{carAccess}` | 판단 카드 JSON | api | api | 60s |
| `kakao:eta:{o}:{d}:{yyyyMMddHHmm}` | 카카오 ETA JSON | api | api | 20분 |

날짜는 모두 KST. `{P}` ∈ EX · KORAIL · KMA · AIRKOREA · KAKAO.
