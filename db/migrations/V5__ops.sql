-- V5: 운영 (FR-201~206, FR-701)
CREATE TABLE ops.collect_job (
  job_name         varchar(40) PRIMARY KEY,
  provider         varchar(12) NOT NULL,
  cron             varchar(30) NOT NULL,
  description      varchar(120),
  enabled          boolean     NOT NULL DEFAULT true,
  expected_per_day int,                   -- 완전성 분모 (결측 탐지 대상 작업만)
  last_run_at      timestamptz,
  last_status      varchar(14) CHECK (last_status IN ('OK', 'PARTIAL', 'FAILED', 'SKIPPED_QUOTA', 'RUNNING')),
  last_duration_ms int,
  last_calls       int,
  last_rows        int,
  last_message     text
);

CREATE TABLE ops.job_run (
  run_id      bigserial   PRIMARY KEY,
  job_name    varchar(40) NOT NULL,
  trigger     varchar(10) NOT NULL CHECK (trigger IN ('SCHEDULE', 'ADMIN', 'BACKFILL', 'STARTUP')),
  started_at  timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  status      varchar(14) NOT NULL,
  calls       int NOT NULL DEFAULT 0,
  rows        int NOT NULL DEFAULT 0,
  message     text
);
CREATE INDEX job_run_job ON ops.job_run (job_name, started_at DESC);

-- 공급자별 일일 예산. 실시간 예약은 Redis INCRBY (ADR-002), 여기는 확정값
CREATE TABLE ops.quota_budget (
  provider    varchar(12) NOT NULL,
  day         date        NOT NULL,
  daily_limit int         NOT NULL,
  used        int         NOT NULL DEFAULT 0,
  reserved    int         NOT NULL DEFAULT 0,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, day)
);

CREATE TABLE ops.slot_gap (
  job_name      varchar(40) NOT NULL,
  series_key    varchar(60) NOT NULL,     -- 예: SEL-DJN:DN
  slot_ts       timestamptz NOT NULL,
  detected_at   timestamptz NOT NULL DEFAULT now(),
  backfilled_at timestamptz,
  attempts      smallint    NOT NULL DEFAULT 0,
  reason        varchar(40),
  PRIMARY KEY (job_name, series_key, slot_ts)
);
CREATE INDEX slot_gap_open ON ops.slot_gap (job_name, slot_ts) WHERE backfilled_at IS NULL;

-- 모든 외부 호출 기록 (FR-206). 키는 마스킹, 90일 보관 (NFR-05)
CREATE TABLE ops.api_call (
  call_id       bigserial   PRIMARY KEY,
  job_name      varchar(40),
  provider      varchar(12) NOT NULL,
  endpoint      varchar(80) NOT NULL,
  params_masked jsonb       NOT NULL,
  http_status   int,
  result_code   varchar(20),
  latency_ms    int,
  rows          int,
  error         text,
  called_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX api_call_time ON ops.api_call USING brin (called_at);
CREATE INDEX api_call_err ON ops.api_call (called_at DESC) WHERE error IS NOT NULL OR http_status <> 200;

CREATE TABLE ops.backfill (
  backfill_id   varchar(26) PRIMARY KEY,
  provider      varchar(12) NOT NULL,
  job_name      varchar(40) NOT NULL,
  from_date     date        NOT NULL,
  to_date       date        NOT NULL,
  planned_calls int         NOT NULL,
  status        varchar(10) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
  requested_at  timestamptz NOT NULL DEFAULT now(),
  finished_at   timestamptz,
  done_days     int         NOT NULL DEFAULT 0,
  message       text
);

-- 작업 목록 (4-3). cron 은 Asia/Seoul 기준. 수집기는 기동 시 이 표를 읽어 스케줄을 등록한다.
INSERT INTO ops.collect_job (job_name, provider, cron, description, expected_per_day) VALUES
  ('toll_unit_sync',    'EX',       '0 4 * * 1',     '톨게이트 목록·좌표 주 1회 갱신', NULL),
  ('road_travel_time',  'EX',       '*/10 * * * *',  '구간 통행시간 꼬리 수집 → 코리도 합산', 288),
  ('road_gap_backfill', 'EX',       '7 */2 * * *',   '결측 슬롯 구간 전체 재조회 (당일 한정)', NULL),
  ('road_volume_all',   'EX',       '2-59/15 * * * *','전국 교통량 15분', 96),
  ('road_incident_sms', 'EX',       '*/5 * * * *',   '실시간 문자 안내', NULL),
  ('rail_daily',        'KORAIL',   '30 3 * * *',    '전일 운행계획·운행정보 → 정시성', 1),
  ('weather_vilage',    'KMA',      '15 2-23/3 * * *','단기예보 (발표 +15분)', 8),
  ('air_quality_sido',  'AIRKOREA', '15 * * * *',    '시도별 실시간 대기질', 24),
  ('kakao_eta',         'KAKAO',    '20 */1 * * *',  '카카오 미래 운행 정보 (교차검증)', 24),
  ('baseline_daily',    '-',        '30 4 * * *',    '기준선 p50·p90 재계산', NULL),
  ('backtest_daily',    '-',        '45 4 * * *',    '최근 28일 백테스트 MAE', NULL),
  ('maintenance',       '-',        '10 0 25 * *',   '다음 달 파티션 생성 · 90일 지난 호출 로그 삭제', NULL);
