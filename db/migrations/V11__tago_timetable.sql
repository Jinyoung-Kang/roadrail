-- V11: 역 쌍 · 날짜별 실제 시간표 (국토교통부 TAGO 열차정보 GetStrtpntAlocFndTrainInfo)
-- 코레일 운행계획은 시발·종착 계획 시각만 있어 중간역 지연을 보간(P-i1, 추정)해 왔다.
-- TAGO 시간표는 출발역 계획 출발 · 도착역 계획 도착과 그날 배정된 차종(grade)을 주므로,
-- 받아 둔 역 쌍 · 날짜는 중간역도 실제 계획 시각과 정확히 비교한다(basis 'TT'). 없을 때만 P-i1(EST, ⚠).

CREATE TABLE IF NOT EXISTS rail.tt_plan (
  dep_stn_cd  varchar(10) NOT NULL,
  arr_stn_cd  varchar(10) NOT NULL,
  dep_date    date        NOT NULL,          -- 출발역 계획 출발 날짜 (TAGO 조회 날짜)
  trn_no      varchar(8)  NOT NULL,
  plan_dep_at timestamptz NOT NULL,
  plan_arr_at timestamptz NOT NULL,
  grade       varchar(30),                   -- 그날 배정 차종 (TAGO traingradename)
  fetched_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (dep_stn_cd, arr_stn_cd, dep_date, trn_no)
);

-- 조회한 역 쌍 · 날짜 (0건이어도 기록 — 다시 부르지 않게). trains = NULL 이면 TAGO 에 역이 없어 조회 불가
CREATE TABLE IF NOT EXISTS rail.tt_fetch (
  dep_stn_cd varchar(10) NOT NULL,
  arr_stn_cd varchar(10) NOT NULL,
  dep_date   date        NOT NULL,
  trains     int,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (dep_stn_cd, arr_stn_cd, dep_date)
);

-- 반환 열(grade)이 늘어나므로 지우고 다시 만든다. 계산 규칙:
--   출발역 · 도착역 각각  EXACT(시발·종착 = 코레일 운행계획) → TT(TAGO 시간표) → EST(P-i1 보간) → NONE
DROP FUNCTION IF EXISTS rail.od_trips(text, text, date, date);
CREATE FUNCTION rail.od_trips(p_dep text, p_arr text, p_from date, p_to date)
RETURNS TABLE (
  run_ymd date, trn_no varchar, act_dep_at timestamptz, act_arr_at timestamptz,
  est_plan_dep_at timestamptz, est_plan_arr_at timestamptz,
  dep_delay_min numeric, arr_delay_min numeric, dep_basis text, arr_basis text, ride_min numeric, grade varchar
) LANGUAGE sql STABLE AS $$
  WITH t AS (
    SELECT a.run_ymd, a.trn_no, a.dep_at AS t_a, b.arr_at AS t_b,
           tp.dep_stn_cd, tp.arr_stn_cd, tp.act_dep_at AS t0, tp.act_arr_at AS t1,
           tp.dep_delay_min AS d0, tp.arr_delay_min AS d1,
           x.plan_dep_at AS tt_dep, x.plan_arr_at AS tt_arr, x.grade
    FROM rail.run_info a
    JOIN LATERAL (
      SELECT b.arr_at FROM rail.run_info b
      WHERE b.run_ymd = a.run_ymd AND b.trn_no = a.trn_no AND b.run_seq > a.run_seq
        AND b.stn_cd = p_arr AND b.arr_at IS NOT NULL
      ORDER BY b.run_seq LIMIT 1) b ON true
    LEFT JOIN rail.train_punctuality tp
      ON tp.run_ymd = a.run_ymd AND tp.trn_no = a.trn_no AND tp.status = 'OK'
     AND tp.act_dep_at IS NOT NULL AND tp.dep_delay_min IS NOT NULL
    LEFT JOIN LATERAL (
      -- 같은 열차 번호 · 출발역 출발 날짜는 운행일 또는 다음 날(자정 넘김) · 실제 출발과 12시간 안
      SELECT x.plan_dep_at, x.plan_arr_at, x.grade FROM rail.tt_plan x
      WHERE x.dep_stn_cd = p_dep AND x.arr_stn_cd = p_arr AND x.trn_no = a.trn_no
        AND x.dep_date BETWEEN a.run_ymd AND a.run_ymd + 1
        AND abs(extract(epoch FROM a.dep_at - x.plan_dep_at)) < 43200
      ORDER BY abs(extract(epoch FROM a.dep_at - x.plan_dep_at)) LIMIT 1) x ON true
    WHERE a.stn_cd = p_dep AND a.dep_at IS NOT NULL AND a.run_ymd BETWEEN p_from AND p_to
  ), d AS (
    SELECT t.*,
      CASE WHEN d0 IS NULL THEN NULL
           WHEN p_dep = dep_stn_cd THEN d0
           WHEN p_dep = arr_stn_cd THEN d1
           WHEN t1 <= t0 THEN NULL
           ELSE round(d0 + (d1 - d0) * greatest(least(extract(epoch FROM t_a - t0) / extract(epoch FROM t1 - t0), 1), 0), 1)
      END AS dd_i,
      CASE WHEN d0 IS NULL THEN NULL
           WHEN p_arr = dep_stn_cd THEN d0
           WHEN p_arr = arr_stn_cd THEN d1
           WHEN t1 <= t0 THEN NULL
           ELSE round(d0 + (d1 - d0) * greatest(least(extract(epoch FROM t_b - t0) / extract(epoch FROM t1 - t0), 1), 0), 1)
      END AS da_i,
      d0 IS NOT NULL AND p_dep IN (dep_stn_cd, arr_stn_cd) AS dep_exact,
      d0 IS NOT NULL AND p_arr IN (dep_stn_cd, arr_stn_cd) AS arr_exact
    FROM t
  ), e AS (
    SELECT d.*,
      CASE WHEN dep_exact THEN dd_i
           WHEN tt_dep IS NOT NULL THEN round((extract(epoch FROM t_a - tt_dep) / 60)::numeric, 1)
           ELSE dd_i END AS dd,
      CASE WHEN arr_exact THEN da_i
           WHEN tt_arr IS NOT NULL THEN round((extract(epoch FROM t_b - tt_arr) / 60)::numeric, 1)
           ELSE da_i END AS da,
      CASE WHEN dep_exact THEN 'EXACT' WHEN tt_dep IS NOT NULL THEN 'TT' WHEN dd_i IS NOT NULL THEN 'EST' ELSE 'NONE' END AS db,
      CASE WHEN arr_exact THEN 'EXACT' WHEN tt_arr IS NOT NULL THEN 'TT' WHEN da_i IS NOT NULL THEN 'EST' ELSE 'NONE' END AS ab
    FROM d
  )
  SELECT run_ymd, trn_no, t_a, t_b,
         t_a - make_interval(secs => (dd * 60)::double precision),
         t_b - make_interval(secs => (da * 60)::double precision),
         dd, da, db, ab,
         round((extract(epoch FROM t_b - t_a) / 60)::numeric, 1),
         grade
  FROM e
$$;
