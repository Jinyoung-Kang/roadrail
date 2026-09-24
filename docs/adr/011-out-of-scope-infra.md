# ADR-011 Kafka/CDC · ClickHouse/BigQuery · Kubernetes 는 도입하지 않음

- 상태: 채택 (조건부 재검토)

| 기술 | 지금 도입하지 않는 이유 | 도입 조건 |
|---|---|---|
| Kafka / CDC | 생산자 1개(collector) · 소비자 1개(api 캐시)뿐. 명령 전달은 Redis Stream 으로 충분 (ADR-009) | 소비자가 여럿(알림·외부 연계)으로 늘거나 이벤트 재생이 필요할 때 — Debezium 으로 `ts.*` 변경을 발행 |
| ClickHouse / BigQuery | 하루 수만 행, DB 전체 수백 MB. PostgreSQL 월 파티션 + BRIN 으로 조회 수십 ms | 코리도 수백 개 · 1분 단위 원본 · 수년 보관으로 수억 행이 될 때 |
| Kubernetes | 단일 맥 로컬 실행이 요구사항 (NFR-10). 상주 프로세스 1개 · 컨테이너 5개 | 다중 인스턴스 · 무중단 배포가 필요할 때. 이미지·헬스체크·설정이 12-factor 라 매니페스트만 추가하면 됨 |
