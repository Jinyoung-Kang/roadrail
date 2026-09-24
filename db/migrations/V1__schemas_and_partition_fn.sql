-- V1: 스키마 6개 + 월 파티션 생성 함수 (ADR-004)
CREATE SCHEMA IF NOT EXISTS ref;   -- 기준정보 (코리도·영업소·역·환경 지점)
CREATE SCHEMA IF NOT EXISTS ts;    -- 도로 시계열 (월 파티션)
CREATE SCHEMA IF NOT EXISTS rail;  -- 열차 운행계획·운행정보·정시성
CREATE SCHEMA IF NOT EXISTS env;   -- 단기예보·대기질
CREATE SCHEMA IF NOT EXISTS ana;   -- 기준선·예측·백테스트
CREATE SCHEMA IF NOT EXISTS ops;   -- 수집 작업·예산·호출 로그·결측

-- 부모 테이블에 대해 [from_month, from_month + months) 월 파티션을 멱등 생성.
-- 파티션 이름: <schema>.<table>_YYYY_MM  (예: ts.road_travel_time_2026_10)
CREATE OR REPLACE FUNCTION ops.ensure_month_partitions(parent text, from_month date, months int)
RETURNS int LANGUAGE plpgsql AS $$
DECLARE
  m date := date_trunc('month', from_month)::date;
  created int := 0;
  part text;
  i int;
BEGIN
  FOR i IN 0 .. months - 1 LOOP
    part := parent || '_' || to_char(m, 'YYYY_MM');
    IF to_regclass(part) IS NULL THEN
      EXECUTE format('CREATE TABLE %s PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                     part, parent,
                     (m::timestamp AT TIME ZONE 'Asia/Seoul'),
                     ((m + interval '1 month')::timestamp AT TIME ZONE 'Asia/Seoul'));
      created := created + 1;
    END IF;
    m := (m + interval '1 month')::date;
  END LOOP;
  RETURN created;
END $$;

-- date 로 파티션하는 테이블(rail.run_info)용
CREATE OR REPLACE FUNCTION ops.ensure_month_partitions_date(parent text, from_month date, months int)
RETURNS int LANGUAGE plpgsql AS $$
DECLARE
  m date := date_trunc('month', from_month)::date;
  created int := 0;
  part text;
  i int;
BEGIN
  FOR i IN 0 .. months - 1 LOOP
    part := parent || '_' || to_char(m, 'YYYY_MM');
    IF to_regclass(part) IS NULL THEN
      EXECUTE format('CREATE TABLE %s PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                     part, parent, m, (m + interval '1 month')::date);
      created := created + 1;
    END IF;
    m := (m + interval '1 month')::date;
  END LOOP;
  RETURN created;
END $$;
