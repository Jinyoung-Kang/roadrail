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
| [020](020-code-review-architecture-concurrency.md) | 전체 코드 리뷰 — 의존 방향 · 가상 스레드 동시성 · 오류 규약 · 외부 예산 클라이언트별 몫 |
| [021](021-dependabot-auto-merge.md) | Dependabot minor · patch 자동 병합 — 필수 검사 하나(ci passed)로 게이트 |
| [022](022-web-major-upgrades.md) | 웹 메이저 업그레이드 — Next 16(webpack 빌드) · Tailwind 4(v3 모양 유지) · TypeScript 7 · React 18 유지 |
| [023](023-route-traffic-and-nowcast.md) | 기능 고도화 — 자동차 경로 구간별 소통(지도 색) · 초단기 날씨 · 경찰청 UTIC 돌발 · 보유 API 검토 |
| [024](024-review-2026-10-reliability.md) | 코드 리뷰(2026-10) — 원천 날짜 꼬리 커서 · 결측 사유 영역 · 예산 복원 · 요청 한도 버킷 · 웹 조회 상태를 React 밖으로 |
| [025](025-layered-architecture.md) | 계층 구조 — 기능 단위 패키지(web → app → data) · 화면과 로직 분리 · Node 24 · Python 3.13 |
| [026](026-qa-2026-10-release.md) | 출시 기준 QA — DB 소켓 읽기 제한 · 수집기 풀 연결 확인 · 조회 상한 · 겹치는 백필 409 · 프록시 오류 규약 · 라디오 그룹 키보드 · 정보 글자 대비 |
| [027](027-db-backup-restore.md) | DB 백업 · 복원 — pg_dump 사용자 지정 형식 · 별도 DB 리허설 · 이름 바꿔 끼우기 |
| [028](028-public-hardening-redis8.md) | 공개 배포 보호 — 수집 상태 오류 상세는 관리 토큰 뒤로 · API 문서 기본 끔 · Redis 비밀번호 · Redis 8 |
