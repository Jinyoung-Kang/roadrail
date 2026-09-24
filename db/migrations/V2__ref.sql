-- V2: 기준정보 (FR-101~104)
CREATE TABLE ref.toll_unit (
  unit_code   varchar(6)  PRIMARY KEY,
  unit_name   varchar(40) NOT NULL,
  route_no    varchar(6),
  route_name  varchar(40),
  lat         double precision,
  lon         double precision,
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ref.corridor (
  corridor_id varchar(20) PRIMARY KEY,
  name        varchar(40) NOT NULL,
  origin_city varchar(20) NOT NULL,
  dest_city   varchar(20) NOT NULL,
  sort_order  smallint    NOT NULL DEFAULT 0,
  active      boolean     NOT NULL DEFAULT true
);

-- 방향별 영업소 구간 체인. 코리도 통행시간 = Σ 구간 통행시간 (6-2, ADR-008)
CREATE TABLE ref.corridor_road (
  corridor_id     varchar(20) NOT NULL REFERENCES ref.corridor ON DELETE CASCADE,
  direction       char(2)     NOT NULL CHECK (direction IN ('DN', 'UP')),
  seq             smallint    NOT NULL,
  start_unit_code varchar(6)  NOT NULL REFERENCES ref.toll_unit,
  end_unit_code   varchar(6)  NOT NULL REFERENCES ref.toll_unit,
  distance_km     numeric(6,1) NOT NULL,
  PRIMARY KEY (corridor_id, direction, seq)
);
CREATE INDEX corridor_road_seg_idx ON ref.corridor_road (start_unit_code, end_unit_code);

CREATE TABLE ref.station (
  stn_cd  varchar(10) PRIMARY KEY,
  stn_nm  varchar(30) NOT NULL,
  lat     double precision,
  lon     double precision,
  source  varchar(10)            -- KAKAO | RUNINFO(좌표 없음)
);

CREATE TABLE ref.corridor_rail (
  corridor_id varchar(20) NOT NULL REFERENCES ref.corridor ON DELETE CASCADE,
  direction   char(2)     NOT NULL CHECK (direction IN ('DN', 'UP')),
  dep_stn_cd  varchar(10) NOT NULL REFERENCES ref.station,
  arr_stn_cd  varchar(10) NOT NULL REFERENCES ref.station,
  PRIMARY KEY (corridor_id, direction)
);

CREATE TABLE ref.corridor_env_point (
  corridor_id varchar(20) NOT NULL REFERENCES ref.corridor ON DELETE CASCADE,
  role        varchar(6)  NOT NULL CHECK (role IN ('origin', 'dest')),
  name        varchar(30) NOT NULL,
  lat         double precision NOT NULL,
  lon         double precision NOT NULL,
  nx          smallint    NOT NULL,   -- 기상청 격자 (FR-104)
  ny          smallint    NOT NULL,
  sido_name   varchar(10) NOT NULL,   -- 에어코리아 시도별 조회 단위
  PRIMARY KEY (corridor_id, role)
);
