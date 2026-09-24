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
| `trip:{from}:{to}:{departIn}:{access}` | 출발지→도착지 판단 카드 (pending 이면 저장 안 함) | api | api | 60s |
| `kakao:addr:{q}` · `kakao:kw:{q}` | 지역 · 장소 검색 결과 | api | api | 1일 |
| `kakao:c2r:{lat,lon}` | 좌표 → 행정구역 (에어코리아 시도) | api | api | 30일 |
| `kakao:many:{origins\|destinations}:{좌표}:{역 코드들}` | 역까지 · 역에서 실제 운전 시간 (다중 길찾기) | api | api | 20분 |
| `kakao:route:{avoid}:{detail}:{o}:{d}:{출발}` | 도로 분석 경로 (도로별 구간 · 소통) | api | api | 20분 |
| `tago:stn:{역명}` · `tago:tt:{역 ID}:{요일}:{U\|D}` | TAGO 지하철 역 목록 · 역별 시간표 | api | api | 7일 · 1일 |
| `kma:{nx}:{ny}:{base}` | 조회 시점 단기예보 (수집 대상이 아닌 격자) | api | api | 3시간 |
| `air:{sido}` | 조회 시점 대기질 (수집 대상이 아닌 시도) | api | api | 50분 |
| `rr:osm:tile:{0-3}` | OSM 선로 구역 응답 (zlib + base64, way id · 노드 · 좌표) — 공개 미러가 느릴 때 다시 실행하면 못 받은 구역만 받음 | collector | collector | 3일 |
| `tago:train:nodes` | TAGO 열차정보 역명 → 역 ID (전국 16개 시도 목록) — 역 쌍 시간표를 부를 때 씀 | api | api | 7일 |
| `rl:{bucket}:{ip}:{분}` | IP 별 분당 요청 수 (bucket = trip · route · search · rail · admin) — INCR + EXPIRE Lua | api | api | 70초 |
| `rail:punct:v2:{dep}:{arr}:{from}:{to}:{groupBy}:{thr}` | 역 쌍 정시율 응답 (시간표를 받는 중이면 저장 안 함) | api | api | 10분 |
| `rail:trains:v2:{dep}:{arr}:{date\|latest}` | 역 쌍 날짜별 운행 응답 (같은 조건) | api | api | 10분 |

날짜는 모두 KST. `{P}` ∈ EX · KORAIL · KMA · AIRKOREA · KAKAO(길찾기) · KAKAO_LOCAL(검색) · TAGO(지하철정보).
