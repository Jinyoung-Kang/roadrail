-- V8: (1) 역 쌍별 실제 선로 경로 — OpenStreetMap 철도 선로(railway=rail)를 따라 계산한 경로 (© OpenStreetMap contributors, ODbL)
--     열차가 연달아 정차하는 역 쌍마다 한 줄. 지도에 기차 경로를 직선 대신 선로대로 그리는 데 쓴다.
--     (2) 작업 실행의 전체 오류 내용 (스택 트레이스 · 실패한 외부 호출) — 수집 상태 화면의 '오류 상세'
CREATE TABLE ref.rail_link (
  dep_stn_cd   varchar(10) NOT NULL,
  arr_stn_cd   varchar(10) NOT NULL,
  path         jsonb       NOT NULL,     -- [[lat, lon], …] (단순화)
  length_km    numeric(7,2) NOT NULL,
  straight_km  numeric(7,2) NOT NULL,
  source       varchar(10) NOT NULL DEFAULT 'OSM',
  computed_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (dep_stn_cd, arr_stn_cd)
);

ALTER TABLE ops.job_run ADD COLUMN IF NOT EXISTS detail text;

INSERT INTO ops.collect_job (job_name, provider, cron, description, expected_per_day) VALUES
  ('rail_geometry', 'OSM', '0 5 1 * *', '철도 선로 형상(OSM) → 역 쌍별 실제 선로 경로 (매월)', NULL)
ON CONFLICT (job_name) DO NOTHING;
