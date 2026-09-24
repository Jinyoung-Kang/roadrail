# ADR-004 PostgreSQL 월 파티션 + BRIN, ClickHouse 미도입

- 상태: 채택
- 결정: 시계열 테이블(`ts.*`, `rail.run_info`, `env.weather_fcst`)을 월 RANGE 파티션으로 두고 `slot_ts` BRIN 인덱스를 붙인다. 파티션은 `ops.ensure_month_partitions()` 로 기동·매월 25일에 만든다 (pg_partman 없이).
- 규모: 원본 구간 통행시간 약 3만 행/일, 코리도 합산 4,600행/일, 운행정보 1만 행/일. 첫날 DB 172MB.
- 재검토 조건: 코리도를 수백 개로 늘리거나 원본을 1분 단위로 받게 되면 ClickHouse(ADR-011).
