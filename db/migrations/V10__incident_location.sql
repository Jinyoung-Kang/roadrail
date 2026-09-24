-- V10: 돌발 안내의 위치 (도로공사 문자 안내 응답의 latitude · altitude(실제로는 경도) · accPointNM)
-- 좌표가 없는 안내는 NULL 로 둔다 (위치를 추정하지 않음).
ALTER TABLE ts.road_incident
  ADD COLUMN IF NOT EXISTS lat double precision,
  ADD COLUMN IF NOT EXISTS lon double precision,
  ADD COLUMN IF NOT EXISTS point_name varchar(120);
CREATE INDEX IF NOT EXISTS road_incident_seen ON ts.road_incident (last_seen_at DESC);

-- 선로 형상: 매일 확인하되 28일 안에 계산했으면 건너뛴다 → 공개 서버 장애로 실패해도 다음 날 다시 시도
UPDATE ops.collect_job SET cron = '0 5 * * *',
       description = '철도 선로 형상(OSM) → 역 쌍별 실제 선로 경로 (28일마다, 실패하면 다음 날 재시도)'
 WHERE job_name = 'rail_geometry';
