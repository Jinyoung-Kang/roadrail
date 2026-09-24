-- V3: 도로 시계열 (월 파티션 + BRIN, NFR-05)

-- 영업소 간 통행시간 원본 (realUnitTrtm, 도착기준 5분 슬롯). 자연키 멱등 저장 (ADR-001)
CREATE TABLE ts.road_travel_time (
  slot_ts         timestamptz NOT NULL CHECK (extract(epoch FROM slot_ts)::bigint % 300 = 0),
  start_unit_code varchar(6)  NOT NULL,
  end_unit_code   varchar(6)  NOT NULL,
  car_type        varchar(2)  NOT NULL,
  travel_sec      int         NOT NULL CHECK (travel_sec > 0),   -- timeAvg
  min_sec         int,                                            -- timeMin
  max_sec         int,                                            -- timeMax
  vehicles        int,                                            -- efcvTrfl
  quality         varchar(8)  NOT NULL DEFAULT 'OK' CHECK (quality IN ('OK', 'SUSPECT')),
  collected_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (slot_ts, start_unit_code, end_unit_code, car_type)
) PARTITION BY RANGE (slot_ts);
CREATE INDEX road_travel_time_brin ON ts.road_travel_time USING brin (slot_ts);
CREATE INDEX road_travel_time_seg ON ts.road_travel_time (start_unit_code, end_unit_code, slot_ts DESC);

-- 코리도·방향 통행시간 (구간 합). 분석·예측의 기본 시계열
CREATE TABLE ts.road_corridor_tt (
  slot_ts        timestamptz NOT NULL CHECK (extract(epoch FROM slot_ts)::bigint % 300 = 0),
  corridor_id    varchar(20) NOT NULL,
  direction      char(2)     NOT NULL,
  travel_sec     int         NOT NULL CHECK (travel_sec > 0),
  observed_segs  smallint    NOT NULL,   -- 이 슬롯에 실측(OK)으로 채운 구간 수
  total_segs     smallint    NOT NULL,
  quality        varchar(8)  NOT NULL CHECK (quality IN ('OK', 'FILLED')),
  computed_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (slot_ts, corridor_id, direction)
) PARTITION BY RANGE (slot_ts);
CREATE INDEX road_corridor_tt_brin ON ts.road_corridor_tt USING brin (slot_ts);
CREATE INDEX road_corridor_tt_key ON ts.road_corridor_tt (corridor_id, direction, slot_ts DESC);

-- 전국 교통량 (trafficAll, 15분 단위)
CREATE TABLE ts.road_volume (
  slot_ts      timestamptz NOT NULL,
  ex_div_code  varchar(4)  NOT NULL,
  tcs_type     varchar(4)  NOT NULL,
  car_type     varchar(2)  NOT NULL,
  volume       int         NOT NULL,
  collected_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (slot_ts, ex_div_code, tcs_type, car_type)
) PARTITION BY RANGE (slot_ts);
CREATE INDEX road_volume_brin ON ts.road_volume USING brin (slot_ts);

-- 실시간 문자 안내 (본문 해시로 중복 제거). 건수가 작아 파티션하지 않음
CREATE TABLE ts.road_incident (
  msg_hash      char(64)    PRIMARY KEY,
  sent_at       timestamptz NOT NULL,
  type_code     varchar(4),
  type_name     varchar(20),
  route_no      varchar(6),
  route_name    varchar(40),
  direction_txt varchar(30),
  process_name  varchar(10),
  content       text        NOT NULL,
  corridor_ids  text[]      NOT NULL DEFAULT '{}',
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX road_incident_sent ON ts.road_incident (sent_at DESC);
CREATE INDEX road_incident_corr ON ts.road_incident USING gin (corridor_ids);

SELECT ops.ensure_month_partitions('ts.road_travel_time', (now() AT TIME ZONE 'Asia/Seoul')::date - 31, 4);
SELECT ops.ensure_month_partitions('ts.road_corridor_tt', (now() AT TIME ZONE 'Asia/Seoul')::date - 31, 4);
SELECT ops.ensure_month_partitions('ts.road_volume',      (now() AT TIME ZONE 'Asia/Seoul')::date - 31, 4);
