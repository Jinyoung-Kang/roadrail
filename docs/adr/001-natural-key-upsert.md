# ADR-001 수집 키는 (슬롯, 대상) 자연키 + ON CONFLICT

- 상태: 채택
- 결정: 시계열 행의 PK 를 `(slot_ts, start_unit_code, end_unit_code, car_type)` 같은 자연키로 두고 `INSERT … ON CONFLICT DO UPDATE` 로 저장한다.
- 이유: 도로공사 통행시간은 하루 전체를 매번 다시 주고, 꼬리 페이지에는 이미 받은 슬롯이 섞인다. 재시도·백필·늦게 공개된 값이 중복 없이 합쳐진다.
- 검증: `test_same_slots_twice_keeps_row_count`, 철도 `compute_day` 재실행 멱등.
- 대안: 자동증가 ID + 사후 중복 제거 — 중복 창이 생기고 집계가 틀어질 수 있어 기각.
