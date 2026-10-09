-- V19: 카카오 예측 소요의 정답 데이터 (도착 신뢰도 3단계, ADR-029). 기존 표는 건드리지 않는다 — 새 표 하나 · 새 작업 한 줄.
--   actual_sec = 같은 출발 시각에 길의 첫 영업소를 떠난 차가 구간을 차례로 따라갔을 때의 소요(궤적 합, 규칙 T-v1 — 도착 슬롯 기준 고정점).
--   값이 없는 구간이 있으면 행을 만들지 않는다(정답에 추정을 섞지 않음). 카카오 예측 원본(ana.kakao_eta)은 90일 뒤 지워지므로
--   kakao_sec 를 함께 둔다 — 이 표는 보존 정리 대상이 아니다(하루 수백 행).
CREATE TABLE ana.kakao_eta_eval (
  depart_at    timestamptz NOT NULL,
  corridor_id  varchar(20) NOT NULL,
  direction    char(2)     NOT NULL,
  requested_at timestamptz NOT NULL,
  kakao_sec    integer     NOT NULL CHECK (kakao_sec > 0),
  actual_sec   integer     NOT NULL CHECK (actual_sec > 0),
  rule         varchar(10) NOT NULL,
  computed_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (depart_at, corridor_id, direction, requested_at)
);

INSERT INTO ops.collect_job (job_name, provider, cron, description, expected_per_day) VALUES
  ('kakao_eta_eval', '-', '55 4 * * *', '카카오 예측 소요 ↔ 고속도로 궤적 합(정답) 짝 만들기 — 최근 7일, 공개 지연 지난 출발만', 1);
