# ADR-007 역할별 언어 분리 (기획서의 'Python 단일 스택' 대체)

- 상태: 채택 (기획서 v0.1 ADR-007 을 대체)
- 결정:
  - **collector · analytics — Python 3.11** (asyncio · httpx · APScheduler · pandas): 비동기 I/O 로 공급자 5종을 수집하고 pandas 로 기준선·백테스트.
  - **api — Java 21 · Spring Boot 4.1** (JdbcClient · Flyway · Redis · springdoc): 조회·판단·관리. 스키마(Flyway) 소유자.
  - **web — Next.js 15 (Pages Router) · React 18 · TypeScript · Tailwind · Recharts · 카카오 지도**.
- 이유: 수집·분석은 Python 생태계가, 동시 요청이 많은 조회 API 와 계약(오류 규약·검증·OpenAPI)은 Spring 이 강하다. 경계는 DB 스키마와 Redis 키 규약(docs/redis-keys.md)으로만 공유한다.
- 비용과 완화: 같은 예측 식이 두 언어에 존재 → 골든 파일 공유 테스트 (ADR-005). 스키마는 한 곳(`db/migrations`)에서만 관리하고 Python 통합 테스트도 같은 SQL 을 적용.
- Spring Boot 버전: 3.4 는 OSS 지원 종료 → 4.1.1 (Jackson 3 · Spring 7). React 18 요구에 맞춰 Next.js 는 Pages Router.
