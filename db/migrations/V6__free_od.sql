-- V6: 어디서 → 어디로 자유 선택 (모든 역 쌍 · 모든 지역)
--   1) 역 좌표 보정 상태 (카카오 키워드 검색, 작업 station_geocode)
--   2) 임의 역 쌍 운행 계산 함수 rail.od_trips — 정시성 규칙 P-i1 의 단일 구현
--      (미리 정한 길의 역 쌍만 매일 계산하던 rail.corridor_trip 을 대체)

-- 두 좌표 사이 직선거리 (km, 하버사인)
CREATE OR REPLACE FUNCTION ops.km(lat1 double precision, lon1 double precision, lat2 double precision, lon2 double precision)
RETURNS double precision LANGUAGE sql IMMUTABLE AS $$
  SELECT 2 * 6371 * asin(sqrt(power(sin(radians(lat2 - lat1) / 2), 2)
         + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lon2 - lon1) / 2), 2)))
$$;

ALTER TABLE ref.station ADD COLUMN IF NOT EXISTS geocoded_at timestamptz;
ALTER TABLE ref.station ADD COLUMN IF NOT EXISTS geocode_note varchar(40);
CREATE INDEX IF NOT EXISTS station_name ON ref.station (stn_nm);

-- 역 A 출발 → 역 B 도착 운행 (같은 열차 · A 가 B 보다 앞). 지연은
--   A·B 가 시발·종착이면 운행계획과 정확 비교(EXACT),
--   중간역이면 시발 출발 지연 d0 와 종착 도착 지연 d1 사이를 실제 운행 경과시간 비율로 보간(EST, ⚠ 추정),
--   운행계획·종착 행이 없으면 NONE.
-- 규칙은 collector 의 roadrail/analytics/punctuality.py corridor_trip() 과 같으며
-- 두 구현이 같은 결과를 내는지 통합 테스트(test_od_trips_matches_python_reference)가 검증한다.
CREATE OR REPLACE FUNCTION rail.od_trips(p_dep text, p_arr text, p_from date, p_to date)
RETURNS TABLE (
  run_ymd date, trn_no varchar, act_dep_at timestamptz, act_arr_at timestamptz,
  est_plan_dep_at timestamptz, est_plan_arr_at timestamptz,
  dep_delay_min numeric, arr_delay_min numeric, dep_basis text, arr_basis text, ride_min numeric
) LANGUAGE sql STABLE AS $$
  WITH t AS (
    SELECT a.run_ymd, a.trn_no, a.dep_at AS t_a, b.arr_at AS t_b,
           tp.dep_stn_cd, tp.arr_stn_cd, tp.act_dep_at AS t0, tp.act_arr_at AS t1,
           tp.dep_delay_min AS d0, tp.arr_delay_min AS d1
    FROM rail.run_info a
    JOIN LATERAL (
      SELECT b.arr_at FROM rail.run_info b
      WHERE b.run_ymd = a.run_ymd AND b.trn_no = a.trn_no AND b.run_seq > a.run_seq
        AND b.stn_cd = p_arr AND b.arr_at IS NOT NULL
      ORDER BY b.run_seq LIMIT 1) b ON true
    LEFT JOIN rail.train_punctuality tp
      ON tp.run_ymd = a.run_ymd AND tp.trn_no = a.trn_no AND tp.status = 'OK'
     AND tp.act_dep_at IS NOT NULL AND tp.dep_delay_min IS NOT NULL
    WHERE a.stn_cd = p_dep AND a.dep_at IS NOT NULL AND a.run_ymd BETWEEN p_from AND p_to
  ), d AS (
    SELECT t.*,
      CASE WHEN d0 IS NULL THEN NULL
           WHEN p_dep = dep_stn_cd THEN d0
           WHEN p_dep = arr_stn_cd THEN d1
           WHEN t1 <= t0 THEN NULL
           ELSE round(d0 + (d1 - d0) * greatest(least(extract(epoch FROM t_a - t0) / extract(epoch FROM t1 - t0), 1), 0), 1)
      END AS dd,
      CASE WHEN d0 IS NULL THEN NULL
           WHEN p_arr = dep_stn_cd THEN d0
           WHEN p_arr = arr_stn_cd THEN d1
           WHEN t1 <= t0 THEN NULL
           ELSE round(d0 + (d1 - d0) * greatest(least(extract(epoch FROM t_b - t0) / extract(epoch FROM t1 - t0), 1), 0), 1)
      END AS da
    FROM t
  )
  SELECT run_ymd, trn_no, t_a, t_b,
         t_a - make_interval(secs => (dd * 60)::double precision),
         t_b - make_interval(secs => (da * 60)::double precision),
         dd, da,
         CASE WHEN dd IS NULL THEN 'NONE' WHEN p_dep IN (dep_stn_cd, arr_stn_cd) THEN 'EXACT' ELSE 'EST' END,
         CASE WHEN da IS NULL THEN 'NONE' WHEN p_arr IN (dep_stn_cd, arr_stn_cd) THEN 'EXACT' ELSE 'EST' END,
         round((extract(epoch FROM t_b - t_a) / 60)::numeric, 1)
  FROM d
$$;

DROP TABLE IF EXISTS rail.corridor_trip;

INSERT INTO ops.collect_job (job_name, provider, cron, description, expected_per_day) VALUES
  ('station_geocode', 'KAKAO_LOCAL', '30 5 * * 1', '좌표 없는 기차역을 카카오 키워드 검색으로 보정', NULL)
ON CONFLICT (job_name) DO NOTHING;
