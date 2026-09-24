-- V7: 환승 경로 탐색(CSA)용 하루 시간표.
-- 하루치 전국 여객열차의 역별 계획 시각. 계획 시각이 API 에 없는 중간역은 od_trips 와 같은 규칙(P-i1)으로
--   계획 = 실제 − 보간 지연   (지연 = 시발 출발 지연 d0 → 종착 도착 지연 d1 을 운행 경과시간 비율로 선형 보간)
-- 시발(t0)·종착(t1)에서는 보간값이 운행계획과의 정확한 지연과 같다. 운행계획이 없거나 확인 불가인 열차는
-- 실제 시각을 그대로 쓴다 (planned = false).

-- t 시각의 보간 지연(분). t0 ≥ t1 이거나 값이 없으면 NULL.
CREATE OR REPLACE FUNCTION rail.interp_delay(t timestamptz, t0 timestamptz, t1 timestamptz, d0 numeric, d1 numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN t IS NULL OR t0 IS NULL OR t1 IS NULL OR d0 IS NULL OR d1 IS NULL OR t1 <= t0 THEN NULL
              ELSE d0 + (d1 - d0) * greatest(least(extract(epoch FROM t - t0) / extract(epoch FROM t1 - t0), 1), 0) END
$$;

CREATE OR REPLACE FUNCTION rail.day_stops(p_day date)
RETURNS TABLE (trn_no varchar, run_seq smallint, stn_cd varchar, arr_at timestamptz, dep_at timestamptz, planned boolean)
LANGUAGE sql STABLE AS $$
  SELECT i.trn_no, i.run_seq, i.stn_cd,
         i.arr_at - make_interval(secs => (coalesce(rail.interp_delay(i.arr_at, tp.act_dep_at, tp.act_arr_at,
                                                    tp.dep_delay_min, tp.arr_delay_min), 0) * 60)::double precision),
         i.dep_at - make_interval(secs => (coalesce(rail.interp_delay(i.dep_at, tp.act_dep_at, tp.act_arr_at,
                                                    tp.dep_delay_min, tp.arr_delay_min), 0) * 60)::double precision),
         tp.trn_no IS NOT NULL
  FROM rail.run_info i
  LEFT JOIN rail.train_punctuality tp
    ON tp.run_ymd = i.run_ymd AND tp.trn_no = i.trn_no AND tp.status = 'OK'
   AND tp.act_dep_at IS NOT NULL AND tp.dep_delay_min IS NOT NULL
  WHERE i.run_ymd = p_day
$$;
