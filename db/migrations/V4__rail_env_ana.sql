-- V4: 철도 · 환경 · 분석

-- 여객열차 운행계획 (travelerTrainRunPlan2): 시발역·종착역 계획 시각
CREATE TABLE rail.run_plan (
  run_ymd      date        NOT NULL,
  trn_no       varchar(8)  NOT NULL,
  dep_stn_cd   varchar(10) NOT NULL,
  arr_stn_cd   varchar(10) NOT NULL,
  plan_dep_at  timestamptz NOT NULL,
  plan_arr_at  timestamptz NOT NULL,
  fetched_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_ymd, trn_no)
);

-- 여객열차 운행정보 (travelerTrainRunInfo2): 역별 실제 출발·도착 일시 (U-1 확인: 실제 시각)
-- API 조회 가능 기간이 약 3개월 ~ 1일 전이므로 무기한 보관 (NFR-04, ADR-003)
CREATE TABLE rail.run_info (
  run_ymd       date        NOT NULL,
  trn_no        varchar(8)  NOT NULL,
  run_seq       smallint    NOT NULL,
  stn_cd        varchar(10) NOT NULL,
  stn_nm        varchar(30),
  line_cd       varchar(4),
  line_nm       varchar(20),
  updown_cd     varchar(2),
  stop_type_cd  varchar(2),              -- 01 시발 · 11 여객승하차 · 05 종착
  arr_at        timestamptz,
  dep_at        timestamptz,
  fetched_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_ymd, trn_no, run_seq)
) PARTITION BY RANGE (run_ymd);
CREATE INDEX run_info_stn ON rail.run_info (stn_cd, run_ymd);

-- 열차 단위 정시성 (규칙 P-v1: 시발역 출발 지연 · 종착역 도착 지연, 계획과 정확 비교)
CREATE TABLE rail.train_punctuality (
  run_ymd        date        NOT NULL,
  trn_no         varchar(8)  NOT NULL,
  dep_stn_cd     varchar(10) NOT NULL,
  arr_stn_cd     varchar(10) NOT NULL,
  plan_dep_at    timestamptz NOT NULL,
  plan_arr_at    timestamptz NOT NULL,
  act_dep_at     timestamptz,
  act_arr_at     timestamptz,
  dep_delay_min  numeric(6,1),
  arr_delay_min  numeric(6,1),
  on_time        boolean,                 -- NULL = 운행 확인 불가 (FR-303)
  status         varchar(10) NOT NULL CHECK (status IN ('OK', 'UNVERIFIED')),
  calc_rule      varchar(10) NOT NULL,
  threshold_min  smallint    NOT NULL,
  computed_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_ymd, trn_no)
);

-- 코리도 구간 열차 (A역 → B역 순서로 정차한 열차). 중간역 계획 시각은 API 에 없으므로
-- 지연을 시발 출발 지연과 종착 도착 지연 사이에서 운행시간 비례로 보간 추정 (규칙 P-i1, ⚠ 추정)
CREATE TABLE rail.corridor_trip (
  run_ymd        date        NOT NULL,
  corridor_id    varchar(20) NOT NULL,
  direction      char(2)     NOT NULL,
  trn_no         varchar(8)  NOT NULL,
  act_dep_at     timestamptz NOT NULL,     -- A역 실제 출발
  act_arr_at     timestamptz NOT NULL,     -- B역 실제 도착
  est_plan_dep_at timestamptz,             -- A역 계획 출발 (정확 또는 추정)
  est_plan_arr_at timestamptz,             -- B역 계획 도착 (정확 또는 추정)
  dep_delay_min  numeric(6,1),
  arr_delay_min  numeric(6,1),
  dep_basis      varchar(6)  NOT NULL CHECK (dep_basis IN ('EXACT', 'EST', 'NONE')),
  arr_basis      varchar(6)  NOT NULL CHECK (arr_basis IN ('EXACT', 'EST', 'NONE')),
  ride_min       numeric(6,1) NOT NULL,
  on_time        boolean,
  calc_rule      varchar(10) NOT NULL,
  PRIMARY KEY (run_ymd, corridor_id, direction, trn_no)
);
CREATE INDEX corridor_trip_key ON rail.corridor_trip (corridor_id, direction, run_ymd DESC);

-- 단기예보 (필요 카테고리만: TMP POP PTY SKY PCP)
CREATE TABLE env.weather_fcst (
  base_at   timestamptz NOT NULL,
  fcst_at   timestamptz NOT NULL,
  nx        smallint    NOT NULL,
  ny        smallint    NOT NULL,
  category  varchar(4)  NOT NULL,
  value     varchar(12) NOT NULL,     -- 원문 (범주형·수치형 혼재)
  PRIMARY KEY (base_at, fcst_at, nx, ny, category)
) PARTITION BY RANGE (base_at);
CREATE INDEX weather_fcst_grid ON env.weather_fcst (nx, ny, fcst_at);

CREATE TABLE env.air_quality (
  data_time    timestamptz NOT NULL,
  station_name varchar(20) NOT NULL,
  sido_name    varchar(10) NOT NULL,
  pm10         int,
  pm25         int,
  khai_value   int,
  khai_grade   smallint,
  pm25_grade   smallint,
  PRIMARY KEY (data_time, station_name)
);
CREATE INDEX air_quality_sido ON env.air_quality (sido_name, data_time DESC);

-- 기준선: 같은 코리도·방향·요일·슬롯의 최근 8주 p50·p90 (FR-401)
CREATE TABLE ana.road_baseline (
  corridor_id varchar(20) NOT NULL,
  direction   char(2)     NOT NULL,
  dow         smallint    NOT NULL CHECK (dow BETWEEN 0 AND 7),  -- 1=월 … 7=일, 0=전체 요일(대체 기준선)
  slot_idx    smallint    NOT NULL CHECK (slot_idx BETWEEN 0 AND 287),
  p50_sec     int         NOT NULL,
  p90_sec     int         NOT NULL,
  n           int         NOT NULL,
  window_from date        NOT NULL,
  window_to   date        NOT NULL,
  computed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (corridor_id, direction, dow, slot_idx)
);

-- 백테스트 결과 (FR-404). 재실행 결정성을 위해 입력 기간·모델 버전 저장 (NFR-07)
CREATE TABLE ana.forecast_eval (
  eval_date     date        NOT NULL,
  model         varchar(12) NOT NULL,
  corridor_id   varchar(20) NOT NULL,
  direction     char(2)     NOT NULL,
  horizon_min   smallint    NOT NULL,
  mae_sec       numeric(8,1),
  mape          numeric(6,4),
  n             int         NOT NULL,
  window_from   timestamptz NOT NULL,
  window_to     timestamptz NOT NULL,
  model_version varchar(20) NOT NULL,
  computed_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (eval_date, model, corridor_id, direction, horizon_min)
);

-- 카카오 미래 운행 정보 길찾기 (FR-504, 선택): 출발 예정 시각 기준 자동차 소요시간 — 자체 추정 교차검증용
CREATE TABLE ana.kakao_eta (
  requested_at  timestamptz NOT NULL,
  depart_at     timestamptz NOT NULL,
  corridor_id   varchar(20) NOT NULL,
  direction     char(2)     NOT NULL,
  duration_sec  int         NOT NULL,
  distance_m    int         NOT NULL,
  PRIMARY KEY (depart_at, corridor_id, direction, requested_at)
);

SELECT ops.ensure_month_partitions_date('rail.run_info', (now() AT TIME ZONE 'Asia/Seoul')::date - 124, 7);
SELECT ops.ensure_month_partitions('env.weather_fcst', (now() AT TIME ZONE 'Asia/Seoul')::date - 31, 4);
