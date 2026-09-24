-- V15: 공휴일 달력 (한국천문연구원 특일 정보 getRestDeInfo — 공휴일 · 대체공휴일 · 선거일 등 isHoliday=Y)
-- 쓰는 곳 (실측 근거: 2026-09-24 추석 서울→대전 하행 6시간 24분 · 코레일 운행 931편 = 같은 목요일 875편 + 임시열차)
--   · 도로 기준선: 공휴일을 입력에서 뺀다 (평소 요일 · 시간 기준선이 명절 정체로 오염되지 않게)
--   · 기차 기준 시간표: 평일 목표일에 공휴일(임시열차 포함) 시간표를 쓰지 않는다
--   · 철도 요일별 정시율: 공휴일을 따로 'H'
--   · 지하철 시각: TAGO 요일 코드는 평일·토·일뿐이라 공휴일엔 어느 시간표인지 알 수 없어 표시하지 않는다
--   · 판단 경고: 출발일이 공휴일이면 이름과 함께 알림
CREATE TABLE IF NOT EXISTS ref.holiday (
  day        date PRIMARY KEY,
  name       varchar(40) NOT NULL,
  kind       varchar(4),                 -- 특일 정보 dateKind (01 국경일 등)
  fetched_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO ops.collect_job (job_name, provider, cron, description, expected_per_day) VALUES
  ('holiday_sync', 'KASI', '0 6 1 * *', '공휴일 · 대체공휴일 달력 (한국천문연구원 특일 정보, 작년~내년)', NULL)
ON CONFLICT (job_name) DO NOTHING;
