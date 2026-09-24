# 설계 결정 기록 (ADR)

| # | 결정 |
|---|---|
| [001](001-natural-key-upsert.md) | 수집 키는 자연키 + ON CONFLICT |
| [002](002-quota-budget-redis.md) | 공급자별 예산을 Redis 원자 연산으로 예약 |
| [003](003-keep-rail-forever.md) | 운행정보는 매일 수집 · 영구 보관 |
| [004](004-postgres-partition-brin.md) | PostgreSQL 월 파티션 + BRIN, ClickHouse 미도입 |
| [005](005-interpretable-forecast.md) | 해석 가능한 기준선 모델 + 언어 간 골든 테스트 |
| [006](006-rule-based-decision.md) | 규칙 기반 판단 + 근거 목록 |
| [007](007-polyglot-stack.md) | 역할별 언어 분리 (Python · Java · TypeScript) |
| [008](008-tail-cursor-and-segment-chain.md) | 도로: 구간 체인 합산 + 꼬리 커서 수집 |
| [009](009-command-stream.md) | 관리 명령은 Redis Stream |
| [010](010-kakao-bounded-wait.md) | 카카오 ETA 비동기 + 최대 0.6초 대기 |
| [011](011-out-of-scope-infra.md) | Kafka · ClickHouse · Kubernetes 미도입과 도입 조건 |
| [012](012-road-quality-rule.md) | 도로 품질 규칙 Q-v2 · H-v1 |
| [013](013-free-origin-destination.md) | 어디서 → 어디로: 전국 임의 지점 선택 |
| [014](014-rail-transfers-and-real-access.md) | 기차 환승 경로(CSA) · 역까지 실제 경로 · 전국 도로 분석 |
| [015](015-rail-track-geometry-osm.md) | 기차 경로를 실제 선로로 (OpenStreetMap · 역 쌍 최단 선로) |
| [016](016-data-provenance-and-tago-timetable.md) | 추정 대신 실제 값 — TAGO 열차 시간표(차종 · 역별 계획 시각)와 추정치 정리 |
| [017](017-query-performance.md) | 쿼리 성능 — JIT 끄기 · 역 쌍 해시 조인 · 한 번 계산 · 결과 캐시 |
| [018](018-security-hardening.md) | 보안 점검 — 요청 한도 · X-Forwarded-For 위조 차단 · CSP · SRI · 의존성 감사 · 이미지 분리 |
| [019](019-holiday-calendar.md) | 공휴일 달력 (한국천문연구원 특일 정보) · 서울 버스 노선정보를 쓰지 않은 이유 |
