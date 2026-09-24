# RoadRail — make help
SHELL := /bin/bash
COMPOSE := docker compose
ADMIN_TOKEN = $(shell grep -E '^ADMIN_TOKEN=' .env 2>/dev/null | cut -d= -f2-)

.PHONY: help env up down logs ps seed collect-once rail-backfill reclassify smoke test test-collector test-api e2e capture psql reset build

help: ## 명령 목록
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'

env: ## .env 가 없으면 만들고 ADMIN_TOKEN 을 채운다
	@test -f .env || cp .env.example .env
	@grep -qE '^ADMIN_TOKEN=.+' .env || sed -i.bak "s/^ADMIN_TOKEN=.*/ADMIN_TOKEN=$$(openssl rand -hex 24)/" .env && rm -f .env.bak
	@echo ".env 준비 완료 — API 키를 채웠는지 확인하세요 (README 1장)"

build: env ## 이미지 빌드
	$(COMPOSE) build

up: env ## 전체 기동 (db · redis · api · collector · web) — 첫 기동은 철도 3개월 백필 포함 약 5분
	$(COMPOSE) up -d --build
	@echo "화면 http://localhost:3300 · API 문서 http://localhost:8300/docs · 상태 make ps"

down: ## 중지 (데이터 유지)
	$(COMPOSE) down

ps: ## 컨테이너 · 헬스 상태
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}'
	@curl -s localhost:8300/api/v1/health | python3 -m json.tool || true

logs: ## 수집기 · api 로그
	$(COMPOSE) logs -f --tail=100 collector api

seed: ## 코리도 seed 적용 (멱등 — 두 번째는 변경 0건)
	$(COMPOSE) exec collector roadrail seed

collect-once: ## 작업 1회 실행: make collect-once JOB=road_travel_time
	$(COMPOSE) exec collector roadrail run $(or $(JOB),road_travel_time)

rail-backfill: ## 운행정보 기간 재수집 (관리 API 경유): make rail-backfill FROM=2026-09-01 TO=2026-09-10
	@curl -s -X POST localhost:8300/api/v1/admin/backfill -H "X-Admin-Token: $(ADMIN_TOKEN)" -H 'Content-Type: application/json' \
	  -d '{"provider":"KORAIL","job":"rail_daily","from":"$(FROM)","to":"$(TO)"}' | python3 -m json.tool

reclassify: ## 도로 품질 규칙 재적용 + 코리도 합산 재계산 (API 호출 없음)
	$(COMPOSE) exec collector roadrail reclassify-road

smoke: ## 외부 API 5종 키 스모크 (호스트 Python 표준 라이브러리)
	python3 tools/smoke.py

test: test-collector test-api ## 전체 테스트 (collector 단위·계약·통합 + api JUnit·Testcontainers)

test-collector: ## collector pytest (compose 의 db·redis 사용, roadrail_test DB)
	$(COMPOSE) up -d db redis
	$(COMPOSE) run --rm --no-deps -e TEST_DATABASE_URL=postgresql://roadrail:roadrail@db:5432/roadrail_test \
	  -e TEST_REDIS_URL=redis://redis:6379/15 collector pytest -q

test-api: ## api JUnit + Testcontainers (Docker 필요, JDK 21 은 Gradle 이 자동 설치)
	cd api && ./gradlew --no-daemon test

e2e: ## Playwright 스모크 (스택이 떠 있어야 함, 설치된 Chrome 사용)
	cd web && npm ci --no-audit --no-fund && E2E_CHANNEL=chrome npx playwright test smoke

capture: ## README 스크린샷 갱신 → docs/images
	cd web && CAPTURE=1 E2E_CHANNEL=chrome npx playwright test capture

psql: ## DB 접속
	$(COMPOSE) exec db psql -U roadrail

reset: ## 모든 데이터 삭제 후 재기동 (주의)
	$(COMPOSE) down -v
	$(MAKE) up
