-- V18: 보존 기간 정리 작업(매일) — 사용자 결정(2026-10-04): 계속 쌓이는 기록은 90일 보관 (코드 리뷰 L7, ADR-024).
--   돌발 안내(마지막으로 본 지) · 결측 기록 · 대기질 · 카카오 ETA · 호출 기록 · 실행 이력. 정리 코드는 collector pipeline/analysis.py retention.
--   기존 행은 바꾸지 않는다 — 새 작업 한 줄만 추가(V16 과 같은 방식). 노트북이 잠들어 건너뛰면 수집기가 기동 때 한 번 실행한다.
INSERT INTO ops.collect_job (job_name, provider, cron, description, expected_per_day) VALUES
  ('retention', '-', '20 3 * * *', '보존 기간 정리 — 90일 지난 돌발 안내 · 결측 기록 · 대기질 · 카카오 ETA · 호출 기록 · 실행 이력 삭제', 1);
