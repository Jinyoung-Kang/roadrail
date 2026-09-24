-- V9: 화면 용어 정리 — '코리도' → '길' (V5 에서 넣은 작업 설명)
UPDATE ops.collect_job SET description = '구간 통행시간 꼬리 수집 → 길 합산' WHERE job_name = 'road_travel_time';
