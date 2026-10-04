# ADR-025 계층 구조 — 기능 단위 패키지(web → app → data) · 화면과 로직 분리 · 런타임 업그레이드

- 상태: 채택 (2026-10-04)
- 배경: 코드 리뷰([계획](../review/2026-10-04-review-and-plan.md) 3절 PR 2)에서 API 는 서비스가 웹 계층의 DTO(`web.dto`)에 23곳 의존하고
  `common ↔ config` 가 서로 import 하며, SQL 68문장이 서비스 안에 업무 규칙과 섞여 있었다. 웹은 화면 컴포넌트가 주소를 직접 만들어
  부르고, 상태 · 부수효과 · 데이터 변환이 페이지 안에 있었다(`Tiles.tsx` 439줄 · 컴포넌트 19개). 런타임은 Node 22(2027-04 종료) ·
  Python 3.11(보안 수정만).
- 원칙: 동작은 바꾸지 않는다(공개 경로 · 응답 · SQL 문자열 그대로). 필요가 입증되지 않은 인프라(모듈 분리 빌드 · 메시지 브로커)나
  구현이 하나뿐인 인터페이스는 만들지 않는다.

## 결정

1. **API 는 기능 단위 패키지 + 계층.** `corridor`(길 · 도로 실측 · 길 판단 카드) · `trip`(출발지 → 도착지 판단 · 경로 분석 · 장소 ·
   철도 여정) · `rail`(정시성 · 역 · 시간표) · `env`(날씨 · 대기 · 돌발 · 공휴일) · `ops`(수집 상태 · 관리 · 헬스).
   각 기능 안은 `web`(컨트롤러) → `app`(서비스: 업무 규칙 · 조합) → `data`(저장소: 쿼리 · 행 매핑), 응답 · 요청 레코드는 `model`.
   공통은 `shared`(도구 · 오류 규약 · 설정) · `shared.web`(요청 한도 · 보안 헤더 · traceId · 예외 처리), 순수 규칙은 `domain`,
   외부 API 어댑터는 `external` 그대로.
   - 의존은 바깥 → 안쪽만: web · app · data → model · domain · shared. domain · model 은 바깥 계층을 모르고, external · shared 는 기능을 모른다.
   - 기능 사이에는 다른 기능의 `app` · `model` 만 쓴다(데이터는 그 기능의 서비스를 거친다) — `trip → corridor → rail → env`, `ops` 는 독립, 순환 없음.
   - SQL(`JdbcClient`)은 `data` 에만. 서비스끼리 빌려 쓰던 정적 도우미는 `domain.WeatherCodes` · `shared.Futures` · `shared.Rows` 로.
   - 규칙은 `ArchitectureTest` 가 지킨다 — 새 의존성(ArchUnit) 없이 import 문을 읽어 검사한다(계층 · 데이터 접근 위치 · 기능 순환).
   - 하지 않은 것: Gradle 다중 모듈(빌드가 느려지고 지금 크기에 이득이 없음), 저장소 인터페이스(구현이 하나), 헥사고날 포트.
2. **웹은 화면 · 훅 · 순수 함수 · 클라이언트로.**
   - `lib/api/client.ts`(React 없음): `getJson` · 오류 · 엔드포인트 주소(`api.*`) · 관리 명령. 화면은 주소를 직접 만들지 않는다.
   - `lib/hooks/*`: 상태 · 부수효과 — `useApi`(상태 기계는 `lib/query.ts`, ADR-024) · `useTrip` · `useRailOd` · `useAdminToken` · `useMapFocus` · `useCorridors`.
   - 순수 함수(`node:test`): `lib/trip` · `lib/rail` · `lib/ops` · `lib/map` · `lib/picker` · `lib/places` · `lib/format`.
   - 컴포넌트는 기능별 폴더(`components/trip/*` · `components/ops/*`). 색은 `lib/palette` 한곳(예전 `lib/layers` 가 화면 컴포넌트를 import).
   - 하지 않은 것: SWR · React Query 같은 새 라이브러리(필요한 기능 — 주소별 결과 · 재시도 상한 · 숨긴 탭 멈춤 — 을 `lib/query.ts` 로 충분히),
     React Testing Library(화면 동작은 E2E).
3. **런타임: Node 24 LTS · Python 3.13.** 의존성 버전은 그대로(모두 새 런타임 휠 · 지원 있음), 이미지 · CI · 타입(`@types/node` 24)만.
   Python 은 폐기 예정 경고를 오류로 둔 테스트(`-W error::DeprecationWarning`)까지 통과. React 18 은 ADR-022 대로 유지.

## 결과

- API 패키지: 5개 기능 × (web · app · data · model) + shared · domain · external. 서비스 → 웹 의존 0, SQL 은 저장소 8개(`corridor` 2 · `rail` 3 · `env` 2 · `ops` 1)에만.
- 웹: 화면 파일은 그리기만 — 페이지에서 상태 · 조회 · 변환 코드가 훅 · 순수 함수로 빠졌다. 첫 로드 JS 는 거의 같다(`/` 111.0 → 111.5KB gzip).
- 검증: API 125건(의존 방향 테스트 3건 — 규칙이 헛돌지 않는지 합성 소스로도 시험) · 수집기 123건(3.13) · 웹 단위 35건 · E2E 29건 · 응답 시간 회귀 없음(`tools/bench.py` p50 수집 상태 38.6 · 정시율 5.6 · 역 검색 5.0ms).
