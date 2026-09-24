# ADR-018 보안 점검과 하드닝

- 상태: 채택 (2026-09-25)
- 이미 있던 것: 모든 포트 127.0.0.1 바인딩 · 컨테이너 non-root · 관리 토큰 상수 시간 비교 · actuator 는 health · info 만 · 호출 로그 키 마스킹 · `.env` 비커밋 · 오류 응답에 스택 트레이스 없음.

## 발견과 조치

| 위험 | 조치 | 검증 |
|---|---|---|
| **요청 한도 없음** — 새 출발지·도착지를 반복 요청하면 카카오 · TAGO 하루 예산을 한 사람이 소진, 관리 토큰 무차별 대입 | IP 별 분당 한도 (Redis 고정 창, INCR+EXPIRE Lua 한 번) — trip 40 · route 20 · search 120 · rail 120 · admin 20. 초과 시 429 `RATE_LIMITED` + `Retry-After`. Redis 장애 시 통과(외부 호출은 QuotaGuard 가 별도로 지킴) | `RateLimitInterceptorTest` · `ApiIT` 429 |
| **X-Forwarded-For 위조로 한도 우회** (한도를 넣으며 직접 재현) — Next.js rewrites 는 X-Forwarded-For 를 붙이지 않고 클라이언트 헤더를 그대로 넘김 → 위조 주소마다 새 한도 | `/api/v1` 을 Next API 라우트 프록시로: 클라이언트가 보낸 값은 버리고 **실제 접속 주소로 덮어씀**, 넘기는 헤더는 필요한 것만 · 본문 64KB 제한. API 는 믿는 프록시(루프백 · 사설망)가 붙인 **맨 오른쪽** 값만 사용 | E2E '위조해도 같은 한도' (위조 3개 → 한도 키 1개) |
| 보안 헤더 없음 | CSP(스크립트는 self · 카카오 지도 SDK 호스트만, `unsafe-inline` 없이) · `frame-ancestors 'none'` · nosniff · Referrer-Policy · Permissions-Policy · COOP. Swagger(/docs)는 제외 | E2E '보안 헤더 · 콘솔 오류 없음' — 페이지 6개 CSP 위반 0건, 지도 정상 |
| 외부 CSS 무결성 | Pretendard 에 SRI(sha384). jsDelivr 의 `.min` 은 동적 생성이라 정적 원본 파일로 | 글꼴 적용 확인 |
| 입력 형식 | 역 코드 `[0-9A-Za-z]{0,10}` · 길 ID · groupBy 화이트리스트 (캐시 키 · SQL 매개변수로 쓰임) | `ApiIT` 400 |
| 의존성 취약점 | npm: Next 내장 PostCSS(high) → `overrides` 로 8.5.28 (Next 16 업그레이드 없이) · pip: pytest 9.0.3, 베이스 이미지 pip 26.2.1 · setuptools 84.0.0 | `npm audit` 0건 · `pip-audit` 0건 |
| 운영 이미지에 테스트 도구 · 코드 | collector Dockerfile 을 runtime / test 단계로 분리, 의존성은 pyproject.toml 한 곳 | `make test-collector` 는 test 단계 |
| 비밀번호 하드코딩 | DB 비밀번호를 `.env` `POSTGRES_PASSWORD` 로 (기본값 유지, 로컬 전용) | compose |
| 공급망 · 유출 감시 | CI 에 `pip-audit` · `npm audit --audit-level=high` · gitleaks(전체 이력), Dependabot 주간 | gitleaks 로컬 실행: 커밋 6개 누출 없음 |

## 남긴 것

- Redis 비밀번호 없음 · Swagger 공개 — 127.0.0.1 전용 로컬 서비스라 두었다. 공개 배포 시 Redis `requirepass`, Swagger 비활성(`springdoc.api-docs.enabled=false`), TLS 종단 프록시가 필요.
- 한도는 접속 주소 단위 — 같은 공유기 뒤 사용자는 나눠 쓴다.
