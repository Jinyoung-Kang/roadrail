-- V16: 경찰청 UTIC 돌발정보(일반 도로 포함 사고 · 공사 · 행사 · 통제)를 도로공사 문자 안내와 같은 표에.
--   source: EX(도로공사 문자 — 기존 행 전부) · UTIC
--   end_at: UTIC 종료 예정 시각, lane: 통제 차로 (도로공사 문자에는 없다)
-- 상수 기본값을 가진 열 추가는 표를 다시 쓰지 않는다(PostgreSQL 11+) — 기존 행은 'EX'.
ALTER TABLE ts.road_incident
  ADD COLUMN source varchar(8) NOT NULL DEFAULT 'EX',
  ADD COLUMN end_at timestamptz,
  ADD COLUMN lane   varchar(40);

INSERT INTO ops.collect_job (job_name, provider, cron, description, expected_per_day) VALUES
  ('utic_incident', 'UTIC', '*/5 * * * *', '돌발정보 — 전국 사고 · 공사 · 행사 · 통제, 일반 도로 포함 (경찰청 UTIC)', NULL);
