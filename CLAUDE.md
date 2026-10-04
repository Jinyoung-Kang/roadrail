# RoadRail — 작업 안내 (Claude Code)

"지금 차로 갈까, 기차로 갈까" 판단 서비스. 자세한 설계는 [README.md](README.md) · [docs/adr](docs/adr) · [docs/VERIFICATION.md](docs/VERIFICATION.md).

## 구성

| 서비스 | 위치 | 언어 · 포트 |
|---|---|---|
| collector | `collector/roadrail` (core · providers · pipeline · scheduler) | Python 3.13 · asyncio — 주기 수집 · 분석 |
| api | `api/src/main/java/com/roadrail` (기능 corridor · trip · rail · env · ops × web → app → data + model · shared · domain · external) | Java 21 · Spring Boot 4 — `127.0.0.1:8300` (`/docs` Swagger — `SWAGGER_ENABLED=true` 일 때만) |
| web | `web` (pages · components · lib/api · lib/hooks · lib 순수 함수 · e2e) | Next.js 16 · Node 24 · TypeScript 7 · Tailwind 4 · React 18 — `127.0.0.1:3300` (`/api/v1` 프록시) |
| db · redis | `db/migrations` (Flyway) | PostgreSQL 16 `127.0.0.1:5462` · Redis 8 `127.0.0.1:6409` (비밀번호 `REDIS_PASSWORD`) |

수집기(Python)와 API(Java)는 같은 Redis 키 · Lua 스크립트로 공급자별 일일 호출 예산을 함께 지킨다 — 새 외부 호출은 반드시 이 예산을 거친다.

## 명령

- 기동 · 상태: `make up` · `make ps` · `make logs` · 한 서비스만 다시 빌드 `docker compose up -d --build <svc>`
- 테스트: `make test-collector` (이미지를 다시 빌드한 뒤 컨테이너에서 pytest — 코드를 바꾸면 이 명령으로) · `make test-api` (`cd api && ./gradlew --no-daemon test`, Testcontainers)
- 린트 · 타입: `docker compose --profile test run --rm --no-deps collector-test ruff check --no-cache roadrail tests` · `cd web && npx tsc --noEmit` · 웹 단위 테스트 `cd web && npm test`
- E2E (스택이 떠 있어야 함): `cd web && E2E_CHANNEL=chrome npx playwright test smoke`
- 키 확인: `make smoke` · README 스크린샷: `make capture` (실데이터라 찍은 뒤 README 설명과 대조)
- DB: `docker compose exec -T db psql -U roadrail -d roadrail -c "…"` (읽기 위주)
- 백업 · 복원: `make backup` · `make restore-check FILE=…`(별도 DB 에 복원해 확인) · `make restore FILE=…`(교체 — 확인 질문, 사용자가 요청할 때만)
- 수동 실행: `make collect-once JOB=<job>` 또는 `POST /api/v1/admin/jobs/{job}/run` (헤더 `X-Admin-Token` — 값은 `.env`)

CI 필수 검사는 `ci passed` 하나(collector · api · web · gitleaks 전체 이력 · docker images). CodeQL 은 PR · main · 매주.

## 규칙 (지킬 것)

- **비밀키는 `.env` 에만.** (`ADMIN_TOKEN` · `REDIS_PASSWORD` 는 `make env` 가 만든다) 커밋 · 로그 · 출력에 값을 남기지 않는다(로그는 마스킹). 테스트의 가짜 키는 `SECRET123` 꼴 — 비밀처럼 보이는 문자열은 gitleaks 에 걸린다.
- **적용된 Flyway 마이그레이션은 고치지 않는다.** 바꿀 일은 새 `V{n}__*.sql` (최신 V18). 상수 기본값 열 추가처럼 표를 다시 쓰지 않는 변경을 우선.
- **데이터 원칙** — 화면 값은 공식 API 값 · 그 값으로 계산한 통계 · 명시한 예측만. 모르는 값은 짐작해 채우지 않고 비운다([docs/DATA-PROVENANCE.md](docs/DATA-PROVENANCE.md)).
- 계층(ADR-025): API 는 SQL 을 `<기능>/data` 에만, 서비스는 다른 기능의 `app` 만 부른다(`ArchitectureTest`). 웹 화면은 그리기만 — 주소는 `lib/api/client`, 상태 · 부수효과는 `lib/hooks`, 규칙 · 변환은 순수 함수(`node:test`).
- 새 공급자: `providers/<name>.py` + `base.py` 의 `CONCURRENCY` · `TIMEOUT` · 예산(`config.py` · `application.yml` · `OpsService.PROVIDERS`) + 실제 응답 fixture 계약 테스트 + 키 없으면 호출 없이 건너뛰기.
- 실데이터로 찾은 결함은 `docs/VERIFICATION.md` 에 행을 더하고(발견 · 원인 · 조치 · 확인), 설계 결정은 ADR 로 남긴다. 테스트 수가 바뀌면 README 7장 표도 함께.

## Git · PR

- `main` 에 직접 커밋하지 않는다 — 브랜치 → PR(한국어 본문) → `ci passed` 확인.
- **병합은 사용자가 요청할 때만**, 검증한 커밋으로 고정: `gh pr merge <n> --merge --match-head-commit <sha> --delete-branch`. force-push 하지 않는다.
- 커밋은 주제별로 나눈다(`feat(collector)` · `feat(api,web)` · `docs` …).
