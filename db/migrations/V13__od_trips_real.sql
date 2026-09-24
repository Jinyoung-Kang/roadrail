-- V13: 화면 통계용 — 실제 계획 시각(코레일 운행계획 EXACT · TAGO 시간표 TT)과 비교한 값만.
-- 보간(P-i1, EST)은 추정이므로 계획 시각 · 지연을 비우고 'NONE'(확인 불가)으로 둔다 → 정시율 분모에서 빠진다.
-- (보간은 환승 경로 탐색(rail.day_stops)의 내부 계산에만 남는다. 화면에 나가는 구간 시각은 실제 시간표로 바꾼다.)
CREATE OR REPLACE FUNCTION rail.od_trips_real(p_dep text, p_arr text, p_from date, p_to date)
RETURNS TABLE (
  run_ymd date, trn_no varchar, act_dep_at timestamptz, act_arr_at timestamptz,
  plan_dep_at timestamptz, plan_arr_at timestamptz,
  dep_delay_min numeric, arr_delay_min numeric, dep_basis text, arr_basis text, ride_min numeric, grade varchar
) LANGUAGE sql STABLE AS $$
  SELECT o.run_ymd, o.trn_no, o.act_dep_at, o.act_arr_at,
         CASE WHEN o.dep_basis IN ('EXACT', 'TT') THEN o.est_plan_dep_at END,
         CASE WHEN o.arr_basis IN ('EXACT', 'TT') THEN o.est_plan_arr_at END,
         CASE WHEN o.dep_basis IN ('EXACT', 'TT') THEN o.dep_delay_min END,
         CASE WHEN o.arr_basis IN ('EXACT', 'TT') THEN o.arr_delay_min END,
         CASE WHEN o.dep_basis IN ('EXACT', 'TT') THEN o.dep_basis ELSE 'NONE' END,
         CASE WHEN o.arr_basis IN ('EXACT', 'TT') THEN o.arr_basis ELSE 'NONE' END,
         o.ride_min, o.grade
  FROM rail.od_trips(p_dep, p_arr, p_from, p_to) o
$$;
