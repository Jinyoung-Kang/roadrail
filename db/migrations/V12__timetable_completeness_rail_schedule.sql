-- V12
-- (1) TAGO 시간표는 날짜에 따라 비어 있다(2026-09-25 실측: 서울→대전 09-21 0편 · 09-23 1편, 코레일 운행은 115 · 126편).
--     받은 편수가 코레일 운행 편수의 80% 미만이면 불완전(complete=false)으로 두고 12시간 뒤 다시 받는다.
--     빠진 열차는 추정하지 않는다 — od_trips 가 그 열차만 보간(EST, ⚠)으로 계산한다.
ALTER TABLE rail.tt_fetch ADD COLUMN IF NOT EXISTS complete boolean NOT NULL DEFAULT true;
UPDATE rail.tt_fetch f SET complete = false
 WHERE f.trains IS NOT NULL
   AND f.trains < 0.8 * (SELECT count(*) FROM rail.od_trips(f.dep_stn_cd, f.arr_stn_cd, f.dep_date, f.dep_date));

-- (2) 코레일은 전날 운행정보를 03:30 에는 아직 주지 않았다(2026-09-25: 03:30 0건 → 04:56 931편).
--     하루 세 번 받는다 — 작업은 전날을 매번 다시 받고 없는 날만 채우므로 여러 번 실행해도 결과가 같다(1회 3건).
UPDATE ops.collect_job SET cron = '30 5,9,15 * * *',
       description = '전일 운행계획·운행정보 → 정시성 (05:30 · 09:30 · 15:30, 공개가 늦으면 다음 실행에서)'
 WHERE job_name = 'rail_daily';
