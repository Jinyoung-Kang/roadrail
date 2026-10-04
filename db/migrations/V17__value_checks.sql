-- V17: 값 영역 CHECK 제약 (L11) — 코드가 쓰는 값만 저장되게 하고, 오타 · 뒤바뀐 경위도는 저장 시점에 막는다.
-- 기존 데이터는 바꾸지 않는다. 추가하면서 기존 행을 검사하므로, 어긋난 행이 있으면 마이그레이션이 실패하고
-- 트랜잭션째 되돌려진다(데이터는 그대로). 적용 전 실데이터 점검: 모든 행이 통과(docs/VERIFICATION.md).
-- 표가 작아(수천 행) 검사 중 잠금은 짧다 — NOT VALID + 별도 VALIDATE 로 나눌 이유가 없다(Flyway 는 한 트랜잭션).

-- 결측 사유: 미수집 · 표본 부족 · 원천이 그날을 더는 주지 않음 · 원천에 표본이 없음 (pipeline/road.py)
ALTER TABLE ops.slot_gap ADD CONSTRAINT slot_gap_reason_check
  CHECK (reason IS NULL OR reason IN ('NO_DATA', 'LOW_COVERAGE', 'SOURCE_EXPIRED', 'NO_SAMPLES'));

-- 작업 실행 상태: ops.collect_job.last_status 와 같은 영역
ALTER TABLE ops.job_run ADD CONSTRAINT job_run_status_check
  CHECK (status IN ('RUNNING', 'OK', 'PARTIAL', 'FAILED', 'SKIPPED_QUOTA'));

-- 돌발 출처: 도로공사 문자 · 경찰청 UTIC (V16)
ALTER TABLE ts.road_incident ADD CONSTRAINT road_incident_source_check CHECK (source IN ('EX', 'UTIC'));

-- 경위도 범위 — 모르면 NULL(짐작해 채우지 않음). 경도를 위도 자리에 넣으면(> 90) 막힌다
ALTER TABLE ts.road_incident ADD CONSTRAINT road_incident_coords_check
  CHECK ((lat IS NULL OR lat BETWEEN -90 AND 90) AND (lon IS NULL OR lon BETWEEN -180 AND 180));
ALTER TABLE ref.station ADD CONSTRAINT station_coords_check
  CHECK ((lat IS NULL OR lat BETWEEN -90 AND 90) AND (lon IS NULL OR lon BETWEEN -180 AND 180));
ALTER TABLE ref.toll_unit ADD CONSTRAINT toll_unit_coords_check
  CHECK ((lat IS NULL OR lat BETWEEN -90 AND 90) AND (lon IS NULL OR lon BETWEEN -180 AND 180));
ALTER TABLE ref.corridor_env_point ADD CONSTRAINT corridor_env_point_coords_check
  CHECK ((lat IS NULL OR lat BETWEEN -90 AND 90) AND (lon IS NULL OR lon BETWEEN -180 AND 180));
