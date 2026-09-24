-- V14: 성능 — 역 쌍 운행 계산(rail.od_trips)이 화면 응답의 대부분을 차지했다 (서울→부산 90일 577ms, 철도 분석 화면 p50 1.97초).
--
-- (1) JIT 끄기. EXPLAIN ANALYZE 로 보니 실행 시간의 약 480ms 가 쿼리마다 하는 JIT 컴파일이었다
--     (비용 추정이 jit_above_cost 를 넘는 수만 행 규모 분석 쿼리). JIT 를 끄면 같은 쿼리 574ms → 94ms.
--     이 서비스의 쿼리는 짧고 자주 불리므로 컴파일 비용을 회수하지 못한다 → 데이터베이스 기본값으로 끈다(새 연결부터).
DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET jit = off', current_database()); END $$;

-- (2) 출발역 행마다 LATERAL 로 뒤쪽 정차를 인덱스로 훑던 것을, 출발역 행 × 도착역 행의 해시 조인으로.
--     반환 열 · 규칙은 V11 과 같다 (출발역 다음의 첫 도착역 정차 하나 — DISTINCT ON).
--     실측 (JIT 끔): 서울→부산 90일 112 → 66ms · 대전→동대구 90일 94 → 69ms, 세 역 쌍 모두 결과 행이 V11 과 완전히 같음.
CREATE OR REPLACE FUNCTION rail.od_trips(p_dep text, p_arr text, p_from date, p_to date)
RETURNS TABLE (
  run_ymd date, trn_no varchar, act_dep_at timestamptz, act_arr_at timestamptz,
  est_plan_dep_at timestamptz, est_plan_arr_at timestamptz,
  dep_delay_min numeric, arr_delay_min numeric, dep_basis text, arr_basis text, ride_min numeric, grade varchar
) LANGUAGE sql STABLE AS $$
  WITH t AS (
    SELECT a.run_ymd, a.trn_no, a.dep_at AS t_a, a.arr_at AS t_b,
           tp.dep_stn_cd, tp.arr_stn_cd, tp.act_dep_at AS t0, tp.act_arr_at AS t1,
           tp.dep_delay_min AS d0, tp.arr_delay_min AS d1,
           x.plan_dep_at AS tt_dep, x.plan_arr_at AS tt_arr, x.grade
    FROM (
      -- 출발역 행 × 도착역 행을 (운행일, 열차)로 해시 조인 — 출발역 다음의 첫 도착역 정차 하나만
      SELECT DISTINCT ON (a.run_ymd, a.trn_no, a.run_seq) a.run_ymd, a.trn_no, a.run_seq, a.dep_at, b.arr_at
      FROM rail.run_info a
      JOIN rail.run_info b
        ON b.run_ymd = a.run_ymd AND b.trn_no = a.trn_no AND b.run_seq > a.run_seq
       AND b.stn_cd = p_arr AND b.arr_at IS NOT NULL AND b.run_ymd BETWEEN p_from AND p_to
      WHERE a.stn_cd = p_dep AND a.dep_at IS NOT NULL AND a.run_ymd BETWEEN p_from AND p_to
      ORDER BY a.run_ymd, a.trn_no, a.run_seq, b.run_seq) a
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
