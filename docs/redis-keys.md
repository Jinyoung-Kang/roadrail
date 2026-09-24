# Redis 키 규약 (collector · api 공유)

| 키 | 형식 | 쓰는 쪽 | 읽는 쪽 | TTL |
|---|---|---|---|---|
| `quota:{P}:{yyyymmdd}` | 정수 — 예약 누계(소비 + 미사용 예약) | collector (Lua 원자 예약·환불) · api `QuotaGuard` (같은 Lua) | api (남은 예산 → 429) | 48h |
| `quota:used:{P}:{yyyymmdd}` | 정수 — 실제 호출 수 | collector · api | api (수집 상태) | 48h |
| `rr:lock:{job}` | 실행 토큰 | collector (SET NX EX) | api (→ 409 JOB_RUNNING) | 작업별 (15분~2시간) |
| `rr:commands` | Stream `{type: run_job \| backfill, …}` | api (XADD) | collector (consumer group `collector`, 처리 후 XACK) | — |
| `rr:collector:heartbeat` | ISO 시각 | collector (30초마다) | api `/health` | 90s |
| `ex:tail:{start}-{end}:{yyyymmdd}` | 정수 — 오늘 본 1종 행 수 (꼬리 페이지 커서) | collector | — | 48h |
| `now:{id}:{dir}:{departIn}:{access}:{carAccess}` | 판단 카드 JSON | api | api | 60s |
| `kakao:eta[p]:{o}:{d}:{yyyyMMddHHmm}` | 카카오 ETA JSON (`p` = 경로 좌표 포함) | api | api | 20분 |
| `trip:{from}:{to}:{departIn}:{access}` | 어디서→어디로 판단 카드 (pending 이면 저장 안 함) | api | api | 60s |
| `kakao:addr:{q}` · `kakao:kw:{q}` | 지역 · 장소 검색 결과 | api | api | 1일 |
| `kakao:c2r:{lat,lon}` | 좌표 → 행정구역 (에어코리아 시도) | api | api | 30일 |
| `kma:{nx}:{ny}:{base}` | 조회 시점 단기예보 (수집 대상이 아닌 격자) | api | api | 3시간 |
| `air:{sido}` | 조회 시점 대기질 (수집 대상이 아닌 시도) | api | api | 50분 |

날짜는 모두 KST. `{P}` ∈ EX · KORAIL · KMA · AIRKOREA · KAKAO(길찾기) · KAKAO_LOCAL(검색).
