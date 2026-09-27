# 코드 리뷰 진단 보고서 (2026-09-27)

범위: 저장소 전체 — `api/`(Spring Boot) · `collector/`(Python) · `web/`(Next.js) · `db/migrations/` · Docker · CI · Dependabot.
우선순위: **시스템 아키텍처 = 보안 = 성능 > 가독성**. 모든 지적에 `파일:라인`과 코드 인용을 붙였고, 재현·측정한 것은 그 결과를 적었다.
측정 환경: Apple Silicon · Docker Desktop 29.8 · 로컬 스택(데이터: 철도 운행정보 84.5만 행 · 91일, 도로 5.2만 행).

## 1. 현황 요약

| 구분 | 내용 |
|---|---|
| API | Java 21(Gradle 툴체인) · Spring Boot 4.1.1 / Spring Framework 7.0.9 · JdbcClient · Flyway · Redis · springdoc 3.1.1 · **가상 스레드 사용**(`spring.threads.virtual.enabled`) · Gradle 9.7.1 |
| 수집기 | Python 3.11 · asyncio · httpx 0.28.1 · APScheduler 3.11.0 · pandas 2.3.2 · psycopg 3.2.10 |
| 웹 | Next.js 15.5.26(Pages Router) · React 18.3.1 · TypeScript 5.9.3 · Tailwind 3.4.19 · recharts 3.10.1 · Playwright 1.63.0 |
| 데이터 | PostgreSQL 16(월 파티션 · BRIN) · Redis 7(캐시 · 예산 · 잠금 · 명령 스트림) · Flyway V1~V15 |
| 구조 | 브라우저 → Next(`/api/v1/*` 프록시, 실제 접속 주소를 X-Forwarded-For 로) → Spring Boot(조회 · 판단 · 관리) → PostgreSQL · Redis ← 수집기(스케줄 · 명령 스트림). 스키마는 API(Flyway)가 소유 |

### 빌드 · 테스트 · 린트 · 스캔 (수정 전 기준선)

| 항목 | 명령 | 결과 |
|---|---|---|
| API 테스트 | `./gradlew test --rerun-tasks` | **55개 통과** (9개 클래스, Testcontainers 포함) · 16.5초 · 컴파일 경고 없음(`-Xlint:deprecation`) · 테스트 JVM CDS 경고 1건 |
| 수집기 테스트 | `make test-collector` | **82개 통과** · 30.8초 |
| 수집기 린트 | `ruff check roadrail tests` | 통과 (추가 규칙 참고용: RUF100 미사용 noqa 4 · S608 1) |
| 웹 | `npm run typecheck && npm run build` | 통과 · First Load JS: `/` 107kB · `/road` 226kB · `/rail` 223kB · `/forecast` 220kB · **ESLint 미설정** |
| E2E | `make e2e` (Playwright, 로컬 스택) | **17개 통과** · 18.6초 |
| 의존성 | `npm audit` · `pip-audit`(선언+설치) · Dependabot alerts | **0 · 0 · 0건** |
| 비밀정보 | gitleaks 8.24.3 전체 이력(27커밋) | **유출 0건** · `.env` 미추적 · 로그 키 마스킹 확인 |

### 응답 시간 기준선 (`tools/bench.py`, 15회, API 직접)

| 시나리오 | p50 | p95 |
|---|---|---|
| 수집 상태 `/ops/collect-status` | 85.2ms | 105.2ms |
| 철도 분석 화면 90일 (3요청 동시, 캐시) | 21.6ms | 2,908.9ms(첫 요청: 시간표 받기 대기) |
| 날짜별 운행 서울→대전 | 13.4ms | 35.2ms |
| 역 검색 추천 `/stations?q=대` | 18.3ms | 43.3ms |
| 장소 검색 `/places/search?q=대전`(카카오 캐시 적중) | 18.0ms | 263.0ms |

## 2. 발견 사항

심각도: Critical 0 · **High 2** · Medium 10 · Low 17 (Info 제외). 재현 표시가 있는 항목은 실제로 재현했다.

| ID | 분류 | 심각도 | 위치(파일:라인) | 근거 코드 | 문제와 영향 | 개선안 | 작업량 |
|---|---|---|---|---|---|---|---|
| SEC-01 | 보안(A03 XSS) | **High** | web/components/RouteMap.tsx:81-84 · web/lib/layers.ts:34-35,53 · web/pages/road/index.tsx:74 | ``content: m.label ? `<div …>${dot}${m.label}</div>` : dot`` | 지도 라벨을 **HTML 문자열**로 만들며 이스케이프하지 않는다. 라벨 출처가 URL(`?from=이름~위도~경도`) · API 에코(`from.name`) · 외부 데이터(돌발 유형명) → 공유 링크 한 번으로 DOM 주입. **재현**: `?from=<b style="color:red">…<img src=x onerror=…>` → 지도 안에 빨간 `<b>` 삽입 확인, `onerror` 는 CSP 가 차단(콘솔 위반 로그). 스크립트는 막혔지만 링크 · CSS 주입(피싱 · 화면 위조)은 가능 | 라벨을 DOM 노드(`textContent`)로 만들어 Node 로 넘긴다 · 색은 형식 검증 · E2E 회귀 테스트 | S |
| SEC-02 | 보안(API4 자원 소비) | Medium | api/.../RoadRouteService.java:36-45 · api/src/main/resources/application.yml:83-88 | `for (int off : PROFILE_OFFSETS_MIN) … kakao.route(…)` · `route: ${RATE_LIMIT_ROUTE:20}` | 새 조합 1건 = 카카오 호출 10건. 분당 20회 한도면 한 클라이언트가 **분당 200건** → 공용 일일 예산 5,000건을 약 25분에 소진, 이후 모든 사용자의 자동차 예측이 비는 가용성 공격 | 외부 예산을 쓰는 엔드포인트에 **클라이언트별 일일 한도**(Redis, 0=끔) 추가 | S |
| SEC-03 | 보안(A03 입력 검증) | Medium | api/.../TripService.java:102-106 · TripController.java:42-45,71-76 | `if (p.lat() < 33 \|\| p.lat() > 39 \|\| …)` · `@RequestParam double fromLat` | `NaN` 은 모든 비교가 거짓이라 범위 검증을 통과한다. **재현**: `/trip?fromLat=NaN&fromLon=NaN…` → HTTP 200, `lat:"NaN"`, "가까운 거리(0.0km)" 잘못된 판단 + 카카오 · 기상청 호출(예산 낭비) + 캐시 오염 | `!(lat >= 33 && lat <= 39)` 형태(유한수만 통과) + 회귀 테스트 | S |
| SEC-04 | 보안(심층 방어) | Low | web/pages/api/v1/[...path].ts:39 | `fetch(API + req.url, …)` | 원본 `req.url` 을 그대로 전달 → `/api/v1/` 밖 경로를 upstream 이 판단하게 둔다. **재현**: `/api/v1/..%2f..%2factuator/health` 가 API(Tomcat)까지 도달(Tomcat 기본 설정이 400 으로 막음, JSON 규약 아닌 HTML 응답) | 경로 세그먼트를 허용 문자로 검증하고 다시 조립, 위반은 프록시에서 400(JSON) | S |
| SEC-05 | 보안(정보 노출) | Low | api/.../OpsService.java:106-132 · web/dto/OpsDtos.java:26-31 | `coalesce(r.detail, …)` (스택 트레이스 포함) | 수집 상태 API 가 인증 없이 스택 트레이스 · 외부 호출 파라미터(키는 마스킹)를 준다. 포트가 127.0.0.1 에만 열려 있어 현재는 수용 가능, 공개 배포 시 위험 | 공개 배포 전 관리 토큰으로 보호하도록 README 보안 절에 명시(현 동작 유지) | S |
| SEC-06 | 보안(설정) | Low | docker-compose.yml:29-33 · api/.../KakaoLocalClient.java:45,62 | `redis-server --save "" --appendonly no` · `"kakao:addr:" + q.toLowerCase(…)` | Redis 인증 · maxmemory 없음. 검색어가 캐시 키가 되어 메모리가 계속 늘 수 있다(한도 분당 120회 · TTL 1일로 완화) | 공개 배포 시 `requirepass` · `maxmemory` 를 README 에 명시 | S |
| ARC-01 | 아키텍처(의존 방향) | Medium | api/.../external/KakaoLocalClient.java:4 (외 4개 클라이언트) · service/KakaoMobilityClient.java:1 · web/dto/JourneyDtos.java:3 | `import com.roadrail.service.JsonCache;` · `package com.roadrail.service;` · `import com.roadrail.external.TagoSubwayClient;` | **service ↔ external 패키지 순환**. 외부 HTTP 클라이언트가 service 패키지에 섞여 있고, 표현 계층 DTO 가 외부 클라이언트 타입에 의존 → 계층 경계 불명확, 변경 영향 전파 | JsonCache → `common`(인프라), KakaoMobilityClient → `external`, 지하철 DTO 를 dto 패키지로 (동작 불변 이동) | M |
| ARC-02 | 아키텍처/성능(동시성) | Medium | api/.../RailJourneyService.java:60-72,348-352 · application.yml:15-17 | `dayCache.computeIfAbsent(refDate, d -> { … jdbc.sql("… rail.day_stops(:d)") … })` | 가상 스레드 + JDK 21 에서 `ConcurrentHashMap.computeIfAbsent`(synchronized) 안의 DB I/O 는 **캐리어 스레드를 고정(pinning)**. **재현**(JDK 21, `-Djdk.tracePinnedThreads`): `reason:MONITOR … computeIfAbsent <== monitors:1`. 동시 요청 시 캐리어 고갈 · Hikari 대기와 겹치면 교착 가능 | 계산을 잠금 밖에서(Future 메모이제이션), 같은 날짜는 한 번만 | S |
| ARC-03 | 아키텍처(복원력) | Medium | application.yml:22-24 · common/GlobalExceptionHandler.java:74-78 | `maximum-pool-size: 12` (connection-timeout 기본 30초) | 가상 스레드는 동시 요청 수에 상한이 없어 풀(12)이 차면 요청이 **30초 대기 후 500**. 과부하가 서버 오류로 보이고 응답이 늘어진다 | 연결 대기 3초 + 연결 획득 실패 → **503 + Retry-After**(`UNAVAILABLE`) | S |
| ARC-04 | 아키텍처(수명주기) | Low | TripService.java:41 · RailJourneyService.java:32 · KakaoMobilityClient.java:37 · TimetableService.java:28 · RoadRouteService.java:27 | `Executors.newVirtualThreadPerTaskExecutor()` (닫지 않음) | 빈마다 실행기를 만들고 종료 시 닫지 않는다 → 종료 중 뒤에서 돌던 작업이 닫힌 DataSource 를 만나 예외, 테스트 주입 불가 | 공용 실행기 빈 1개(`destroyMethod=close`)를 주입 | S |
| PERF-01 | 성능(전체 조회) | Medium | api/.../OpsService.java:87-96 | `SELECT (SELECT count(*) FROM ts.road_travel_time) …, (SELECT count(*) FROM rail.run_info) …` | 수집 상태(30초마다 자동 갱신)가 **매번 전체 행 수를 센다**. 측정: p50 85ms, 그중 `run_info` count 63ms(84.5만 행). 운행정보는 영구 보관(ADR-003) → 연 340만 행씩 선형 증가 | 규모 지표는 통계 추정치(`pg_class.reltuples`)와 60초 캐시, 작은 표만 정확히 | S |
| PERF-02 | 성능(반복 집계) | Medium | api/.../RailService.java:322-335 · PlaceService.java:34 | `LEFT JOIN (SELECT stn_cd, count(*) … FROM rail.run_info WHERE run_ymd > :l::date - 7 …)` | 역 검색 · **장소 검색(입력할 때마다)** 마다 7일치 운행정보 7.1만 행을 집계. EXPLAIN 48.6ms(냉), `/stations?q=대` p50 18.3ms. 값은 하루 세 번만 바뀐다 | 최근 운행일 기준 역별 편수를 메모리에 두고(10분 · 운행일 바뀌면 갱신) 이름 필터 · 정렬만 SQL/메모리에서 | S |
| PERF-03 | 성능(알고리즘) | Low | collector/roadrail/analytics/road_quality.py:46-55,104 | `near = sorted(values[k][0] for k in keys if abs(k - t) <= window)` | 튀는 값 제거 · 결측 채움이 **O(n²)** 창 탐색. 10분 꼬리 수집은 작지만 하루 전체 재계산(`backfill_gaps`) · 60일 재분류(`reclassify-road`)는 제곱으로 느려진다 | 정렬 + 이분 탐색/두 포인터로 **O(n log n)**, 결과 동일성 테스트 | S |
| PERF-04 | 성능(중복 계산) | Low | api/.../RailService.java:296-304 | `FROM rail.train_punctuality WHERE run_ymd BETWEEN :f AND :t` | 전국 비교값은 기간 · 기준에만 의존하는데 **역 쌍마다** 다시 계산해 역 쌍 캐시에 중복 저장 | (기간, 기준) 키로 따로 캐시 | S |
| PERF-05 | 성능(번들) | Low | web/pages/road/index.tsx:7 · rail/index.tsx:5 · forecast.tsx:3 | `import { SimpleBars } from "@/components/Charts";` | recharts 가 첫 로드에 포함 → `/road` 226kB · `/rail` 223kB (홈 107kB). 차트는 화면 아래쪽 | `next/dynamic` 으로 차트 지연 로드 | S |
| PERF-06 | 성능(대기 누적) | Low | RailJourneyService.java:181,312,316 · TripService.java:130-131 | `TripService.join(single.get(s.code()), Duration.ofSeconds(2))` (반복문 안) | 병렬로 띄운 작업을 **작업마다 새 제한 시간으로** 기다려 최악 대기가 더해진다(역 6곳 × 2초 = 12초) | 하나의 마감 시각을 공유(TripService.left 와 같은 방식) | S |
| PERF-07 | 성능(왕복) | Low | api/.../external/QuotaGuard.java:45-52 · OpsService.java:175-181 | `redis.execute(RESERVE…); redis.opsForValue().increment(used); redis.expire(…)` | 외부 호출마다 Redis 3회 왕복 · 예산 화면은 공급자당 GET 2회(20회) | 사용 기록을 Lua 에 합쳐 1회 · 예산 조회는 MGET 1회 | S |
| BUG-01 | 오류(알고리즘) | **High** | api/.../domain/RailRouter.java:96-103 | `stn = v[0].from(); if (originSet.contains(stn)) break;` | 여정 복원이 **출발 후보역을 만나면 무조건 멈춘다**. 그 역의 탑승 가능 시각보다 이른 열차에 타는 불가능한 여정을 돌려주고, 올바른 환승 여정(A→B→D)을 잃는다. **재현**: A(10:00) · B(10:30) 후보, T1 A→B, T2 B 10:25→D → `originStn=B`, B 10:25 탑승(불가능). 이후 검증에서 빠져 "이어지는 열차가 없습니다" · 다음 여정 탐색 기준도 틀어짐 | 출발 후보역의 탑승 가능 시각 ≤ 그 구간 출발일 때만 멈추고, 아니면 도착 경로를 계속 따라간다 + 회귀 테스트 | S |
| BUG-02 | 오류(오류 규약) | Medium | api/.../common/GlobalExceptionHandler.java:74-78 | `@ExceptionHandler(Exception.class) … INTERNAL_ERROR` | 표준 MVC 예외를 모르고 전부 500 으로. **재현**: `Content-Type: text/plain` → **500 INTERNAL_ERROR**, `Accept: text/csv` → 빈 406, 둘 다 **ERROR 스택 트레이스** 로그("Failure in @ExceptionHandler"). 클라이언트 오류가 서버 장애로 집계되고 로그 폭주 수단이 된다 | Spring `ErrorResponse` 예외는 그 상태 코드로(415 `UNSUPPORTED_MEDIA_TYPE` 등), 406 은 본문 없이, 로그는 WARN 이하 | S |
| BUG-03 | 오류(장애 격리) | Medium | api/.../external/TagoSubwayClient.java:86,123-126 · service/JsonCache.java:35 · TripService.java:84-86 | `Integer.parseInt(dep.substring(0, 2))` · `if (items == null \|\| items.isMissingNode()) return …arrayNode();` | ① 부가 정보(지하철 시각) 파싱 예외가 캐시 로더 → `join` 재던짐을 거쳐 **/trip 전체를 500** 으로 만든다. ② TAGO 오류 응답(resultCode≠00)을 빈 결과로 보고 **7일/1일 캐시** → 일시 장애가 오래 남는다 | 헤더 resultCode 확인(오류는 null · 캐시 안 함), 잘못된 행은 건너뜀, 부가 정보 실패는 빈 값 + WARN | S |
| BUG-04 | 오류(캐시 키) | Low | api/.../TripService.java:93-94 | `"trip:%.4f,%.4f:%.4f,%.4f:%d:%s"` | 역 지정(`fromStation`)과 이름이 키에 없다 → 같은 좌표 · 다른 역 지정 요청이 앞 결과를 받는다(화면은 이름이 달라 결과를 버리고 대기) | 역 코드 · 이름을 키에 포함 | S |
| BUG-05 | 오류(데이터 유실) | Low | collector/roadrail/pipeline/road.py:81,96-101 | `await r.set(key, seen, ex=172800)` (저장 전) | 꼬리 위치를 **DB 저장 전에** 전진시킨다. 다른 구간 실패(예산 소진 등)로 작업이 끝나면 받아 둔 행이 버려지고 다음 수집도 건너뛴다 — 당일 결측 재수집으로만 복구, 자정 직전이면 영구 유실 | 저장 성공 뒤 꼬리 위치 기록 + 테스트 | S |
| BUG-06 | 오류(잠금) | Low | collector/roadrail/scheduler/jobs.py:111-119,158-159 | `if await r.get(rds.lock_key(name)) == token: await r.delete(…)` | 해제가 비원자적(확인 후 삭제 사이에 만료 · 재획득되면 남의 잠금 삭제). 잠금 획득 뒤 try 밖 DB 호출이 실패하면 잠금이 TTL(최대 2시간)까지 남아 작업이 막힌다 | Lua 비교 후 삭제 · 획득 직후부터 try/finally | S |
| BUG-07 | 오류(동시성) | Medium | api/.../KakaoMobilityClient.java:69-73 · TimetableService.java:55-56 | `inflight.computeIfAbsent(key, k -> supplyAsync(…).whenComplete((e, ex) -> inflight.remove(k)))` | 작업이 `whenComplete` 등록 전에 끝나면 콜백이 `computeIfAbsent` 안에서 `remove` → **IllegalStateException("Recursive update")**(재현). 실패한 future 가 맵에 **영구히 남아** 그 키는 재시작 전까지 항상 '진행 중'(화면이 끝없이 다시 부름) · 결과 없음 | Future 를 먼저 맵에 넣고, 작업 시작 · 제거 콜백은 잠금 밖에서 + 결정적 재현 테스트 | S |
| BUG-08 | 오류(안내 문구) | Low | api/.../NowCardService.java:145 | `" 운행 기준 · 매일 03:30 계산"` | 실제 일정은 05:30 · 09:30 · 15:30(V12) — 사용자에게 틀린 데이터 시각 안내 | 문구를 실제 일정으로 | S |
| CODE-01 | 코드(저장소 위생) | Low | collector/roadrail_collector.egg-info/ (6개 파일) | (빌드 산출물) | 설치 시 생기는 산출물이 커밋됨 | 추적 해제 + .gitignore | S |
| CODE-02 | 코드(정적 분석) | Low | web/package.json:6-12 | `"scripts": { … "typecheck": "tsc --noEmit" … }` | 웹에 ESLint 가 없다(빌드가 린트를 건너뜀, 소스의 `eslint-disable` 주석은 동작하지 않음) | eslint-config-next 도입(새 의존성 → 별도 PR 권장) | M |
| CODE-03 | 코드(중복) | Low | api/.../KakaoMobilityClient.java:63-66,88-91,165-168 | `departAt.withMinute(departAt.getMinute() - departAt.getMinute() % 10)…` ×3 | 10분 정렬 · 캐시 키 조립이 세 번 반복 → `pending()` 키가 어긋나면 조용히 오동작 | 헬퍼 추출 | S |
| CODE-04 | 코드(가독성) | Low | TripService.java:28-35,202 · JourneyDtos.java:13 · RailService.java:56-59 · ApiIT.java:20 | `.replace("dist(", "ops.km(")` · `반경 40km … 최대 4곳 … (환승 제외)` · `ESTIMATE(직선거리 추정)` | SQL 문자열 치환 트릭, 실제와 다른 Javadoc(현재 30km · 6곳 · 환승 포함 · ESTIMATE 없음), 중복 Javadoc, 테스트 주석(403 → 실제 401) | 명시적 SQL · 문서 정정 | S |
| CODE-05 | 코드(린트) | Low | collector/roadrail/pipeline/road.py:162 외 | `f"""UPDATE … WHERE job_name = '{JOB}' …"""` · `# noqa: BLE001`(규칙 미사용) | f-string SQL(상수라 안전하지만 S608), 쓰이지 않는 noqa 4개 | 매개변수화 · noqa 정리 | S |
| CODE-06 | 코드(오류 처리) | Low | web/lib/api.ts:12-14 | `const body = text ? JSON.parse(text) : null;` | JSON 이 아닌 오류 본문(프록시 · Tomcat HTML)이면 SyntaxError 가 그대로 화면에 | 파싱 실패는 상태 코드 기반 오류로 | S |
| CODE-07 | 코드(URL 조립) | Low | web/pages/road/[corridor].tsx:34-35 · forecast.tsx:21 | ``useApi(`/api/v1/corridors/${cid}/road/series?…`)`` | 경로 값(URL 입력)을 인코딩하지 않아 `../` 등으로 다른 API 경로를 부르게 된다(읽기 전용이라 영향 작음) | `encodeURIComponent` | S |
| CODE-08 | 코드(출처 표시) | Low | web/components/Layout.tsx:16-17 · About.tsx:20 | `const SOURCES = ["한국도로공사", …, "OpenStreetMap"];` | 한국천문연구원(특일 정보) 출처 누락 — 공공데이터 출처 표시 | 출처 추가 | S |
| CODE-09 | 코드(경고) | Low | api/src/main/resources/application.yml:42-46 | `springdoc: swagger-ui: path: /docs` | 기동마다 springdoc WARN 2건("enabled by default…") — 의도된 공개 문서인데 설정이 암묵적 | 활성 여부를 명시 | S |
| CODE-10 | 코드(이미지) | Low | collector/Dockerfile:15-17,27-29 | `… > /tmp/req.txt` | 루트 소유 임시 파일이 이미지에 남아 비루트 사용자가 같은 경로를 못 쓴다(실측: 컨테이너에서 pip-audit 실행 실패) | 한 RUN 안에서 삭제 | S |
| CODE-11 | 설정(의존성 갱신) | Low | .github/dependabot.yml:1-21 | `package-ecosystem: docker … schedule: { interval: weekly }` | 19개 PR 동시 생성, 런타임과 맞지 않는 메이저(python 3.14 ↔ `requires-python <3.12`, Node 25 비LTS, @types/node 26 ↔ Node 22, react-is 19 ↔ React 18) | minor/patch 묶음 · 고정한 런타임 이미지의 메이저 제외 · PR 수 제한 | S |
| INFO | 좋은 점 | — | — | — | 자연키 멱등 저장 · Redis Lua 원자 예산 · CSP/SRI · 프록시의 X-Forwarded-For 덮어쓰기 · 키 마스킹 · Testcontainers · 언어 간 골든 테스트 · 스캔 0건. SEC-01 의 스크립트 실행을 CSP 가 실제로 막음 | 유지 | — |

## 3. 개선 계획

### 바로 고칠 항목 (Quick win, S)

1. **보안** SEC-01 지도 라벨 · SEC-03 NaN · SEC-04 프록시 경로 · CODE-07 경로 인코딩 · SEC-02 일일 한도
2. **정확성** BUG-01 여정 복원 · BUG-02 오류 규약 · BUG-03 장애 격리 · BUG-04 캐시 키 · BUG-08 안내 문구
3. **성능** PERF-01 수집 상태 · PERF-02 역 검색 · PERF-04 전국 비교 캐시 · PERF-06 마감 시각 공유 · PERF-07 Redis 왕복 · PERF-05 번들
4. **수집기** BUG-05 꼬리 위치 · BUG-06 잠금 · PERF-03 O(n log n) · CODE-05 · CODE-10

### 구조 개선 항목

1. ARC-01 패키지 순환 제거(이동만, 동작 불변) → ARC-04 공용 실행기 → ARC-02 · BUG-07 동시성 → ARC-03 과부하 503
2. 문서 · 설정: CODE-01 · CODE-04 · CODE-08 · CODE-09 · CODE-11 · SEC-05/06 운영 권고

### 사전 승인 대상과 처리 방침

- DB 스키마 · 마이그레이션 변경: **없음** (모두 코드 · 캐시로 해결)
- 공개 API 변경: 오류 코드 추가(`UNSUPPORTED_MEDIA_TYPE` · `NOT_ACCEPTABLE` · `UNAVAILABLE`) · NaN 좌표 400 · 프록시 잘못된 경로 400 — **추가 · 버그 수정만**, 응답 형식 불변. **기능 변경**: SEC-02 일일 한도(한도 초과 시 429)
- 파일 삭제: CODE-01 빌드 산출물 추적 해제 (생성물)
- 의존성 메이저 업그레이드 · 강제 푸시: **하지 않음** (Dependabot 메이저 PR 은 아래 리뷰로 판단만)

### 보류 (사유)

- CODE-02 ESLint: 새 개발 의존성 수십 개가 들어와 공급망 표면이 넓어진다 → 별도 PR 로 검토 권장
- SEC-05 · SEC-06: 로컬 전용(127.0.0.1) 배포에서는 수용 가능한 위험 → README 보안 절에 공개 배포 체크리스트로 명시

## 4. 기존 PR 리뷰 — Dependabot 19건

| PR | 변경 | CI | 판단 | 근거 |
|---|---|---|---|---|
| #1 #3 #5 #7 #12 | GitHub Actions 메이저(setup-python 7 · checkout 7 · setup-java 6 · upload-artifact 7 · setup-buildx 4) | 통과 | Approve | 워크플로 입력 변경 없음, 러너 호환 |
| #6 | PyYAML 6.0.2 → 6.0.3 | 통과 | Approve | 패치 |
| #11 #15 | psycopg 3.3.6 · pydantic-settings 2.15.0 | 통과 | Approve | 마이너, 통합 테스트 통과 |
| #13 | ruff 0.16.8 | 통과 | Approve | 개발 도구, 새 버전으로 린트 통과 |
| #8 | pandas 3.0.6 (메이저) | 통과 | 보류(승인 필요) | Copy-on-Write · 문자열 dtype 기본값 변경. 테스트는 통과했으나 기준선 · 백테스트 수치 동일성을 실데이터로 비교한 뒤 병합 권장 |
| #2 | Temurin 21 → 25 (빌드 · 런타임) | 통과 | Request changes | Gradle 툴체인은 21 그대로라 이미지 빌드 중 JDK 21 을 따로 내려받는다. 25 로 가려면 툴체인 · CI 를 함께 올리는 별도 PR(JEP 491 로 ARC-02 류 문제 근본 해소) |
| #4 | python 3.14-slim | **실패** | Request changes · 닫기 | `requires-python = ">=3.11,<3.12"` 와 충돌 |
| #9 | TypeScript 7.0.2 | **실패** | Request changes | 빌드 실패, 메이저 |
| #10 | node 25-bookworm-slim | 통과 | Request changes · 닫기 | 25 는 비LTS — LTS(22/24) 유지 |
| #14 | @types/node 26 | 통과 | Request changes · 닫기 | 런타임 Node 22 와 타입 불일치(없는 API 를 허용) |
| #16 | react 19 · @types/react 19 | **실패** | Request changes | react-dom 18 과 불일치, 계획된 React 19 이전 필요 |
| #17 | Next.js 16.3.5 (메이저) | 통과 | 보류(승인 필요) | 빌드는 통과하나 메이저 — E2E 포함 이전 검증 후 |
| #18 | Tailwind 4 | **실패** | Request changes | 설정 · PostCSS 플러그인 구조 변경 필요 |
| #19 | react-is 19 | 통과 | Request changes · 닫기 | React 18 과 메이저 불일치(recharts 피어) |

## 5. 처리 결과

PR 병합 순서: [#20](https://github.com/Jinyoung-Kang/roadrail/pull/20) 보안 → [#21](https://github.com/Jinyoung-Kang/roadrail/pull/21) 정확성 → [#22](https://github.com/Jinyoung-Kang/roadrail/pull/22) 아키텍처·동시성 → [#23](https://github.com/Jinyoung-Kang/roadrail/pull/23) 성능 → [#24](https://github.com/Jinyoung-Kang/roadrail/pull/24) 수집기(독립, `main` 기준) → #25 정리·문서.
#20~#23 · #25 는 앞 PR 위에 쌓은 스택이라 순서대로 병합하면 base 가 자동으로 바뀐다.

| ID | 상태 | PR | 검증 |
|---|---|---|---|
| SEC-01 | 해결 | #20 | E2E 주입 문자열이 글자 그대로 · 주입 요소 0 · 라벨 계산 스타일 동일 |
| SEC-02 | 해결 (기능 변경) | #20 | QuotaGuardIT 3 — 몫 · 전체 한도 · 가상 스레드 상속. 끄기: `QUOTA_CLIENT_SHARE_PCT=0` |
| SEC-03 | 해결 | #20 | ApiIT NaN · ±Infinity → 400 (수정 전 200 재현) |
| SEC-04 | 해결 | #20 | E2E 인코딩한 `../` → JSON 400 |
| SEC-05 · SEC-06 | 문서화 (보류) | #25 | README '공개 배포 체크리스트' — 로컬 전용이라 현 동작 유지 |
| BUG-01 | 해결 | #21 | RailRouterTest +3 · 실제 시간표 3,000쌍: 탈 수 없는 여정 17 · 20건 → 0 |
| BUG-02 | 해결 | #21 | ApiIT 415 · 406 · ERROR 로그 없음(OutputCapture) · 실제 스택 확인 |
| BUG-03 | 해결 | #21 | TagoSubwayClientTest 3 · 성공 응답 헤더(resultCode 00) 실제 호출로 확인 |
| BUG-04 · BUG-08 | 해결 | #21 | ApiIT |
| ARC-01 | 해결 | #22 | 패키지 의존 재집계 — service ↔ external · dto → external 없음 (common ↔ config 는 남김) |
| ARC-02 | 해결 | #22 | `-Djdk.tracePinnedThreads`: Memo 에서 출력 없음 · ConcurrencyToolsTest |
| ARC-03 | 해결 | #22 | GlobalExceptionHandlerTest · connection-timeout 3초 |
| ARC-04 | 해결 | #22 | 재시작 구간 ERROR/WARN 없음 |
| BUG-07 | 해결 | #22 | ConcurrencyToolsTest — 즉시 완료 · 실패 비고착 (수정 전 패턴은 `Recursive update` 재현) |
| CODE-03 | 해결 | #22 | 헬퍼로 통합 |
| PERF-01 · PERF-07 | 해결 (방식 변경) | #23 | 88.5 → 43.3ms. 계획했던 통계 추정치(`reltuples`)는 화면에 추정치를 내지 않는 원칙(ADR-016)과 맞지 않아 **정확한 값 5분 캐시 + 측정 시각**으로 바꿨다 |
| PERF-02 | 해결 | #23 | 역 검색 15.5 → 5.6ms · 정렬 특성 테스트(수정 전 SQL 에서 먼저 통과) |
| PERF-04 | 해결 (확장) | #23 | 전국 비교 캐시 + JsonCache 동시 미적중 합치기(JsonCacheIT) — 철도 화면 첫 로드 231 → 183ms |
| PERF-05 | 해결 | #23 | 분석 화면 첫 로드 JS 226 · 223 · 220 → 103 · 99.4 · 96.8kB |
| PERF-06 | 해결 | #23 | 정상 경로 결과 동일(테스트) |
| BUG-05 · BUG-06 | 해결 | #24 | 통합 테스트 3 (수정 전 2개 실패 재현) · 실제 수집기 기동 작업 11개 OK · 남은 잠금 없음 |
| PERF-03 | 해결 | #24 | 무작위 65세트 결과 동일 · 60일 13,582 → 14.3ms |
| CODE-05 · CODE-10 | 해결 | #24 | `ruff --extend-select RUF100,S608` 통과 · 이미지 /tmp 비어 있음 |
| CODE-01 · CODE-04 · CODE-06 · CODE-08 · CODE-09 · CODE-11 | 해결 | #25 | springdoc 경고 조건은 바이트코드로 확인(`enabled` 필드 기본 false · 엔드포인트는 matchIfMissing) |
| CODE-02 | 보류 | — | ESLint 는 새 개발 의존성이 많아 별도 PR 권장 |

### 최종 상태

| 항목 | 수정 전 | 수정 후 |
|---|---|---|
| API 테스트 (JUnit + Testcontainers) | 55 | **79** |
| 수집기 테스트 (pytest) | 82 | **87** |
| E2E (Playwright) | 17 | **19** |
| 의존성 취약점 · 비밀정보 | 0 · 0 | 0 · 0 |
| 새 경고 | — | 없음 (springdoc 기동 경고 2건은 제거) |
