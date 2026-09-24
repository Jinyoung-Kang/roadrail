# ADR-009 관리 명령은 Redis Stream 으로 collector 에 전달

- 상태: 채택
- 결정: `POST /admin/jobs/{job}/run`, `POST /admin/backfill` 은 api 가 검증(토큰·기간·예산·실행 중 여부)한 뒤 `rr:commands` 스트림에 넣고 202 를 돌려준다. collector 는 consumer group 으로 읽어 실행하고 XACK 한다 (재기동 시 pending 먼저 처리 → at-least-once, 작업은 멱등).
- 이유: 작업 실행 코드는 Python 에만 있다. api 가 HTTP 로 collector 를 부르면 collector 에 서버를 또 띄워야 하고, 재기동 중 요청이 사라진다.
- 409: `rr:lock:{job}` 이 있으면 JOB_RUNNING. 429: 백필 예상 호출 수 > 남은 예산.
