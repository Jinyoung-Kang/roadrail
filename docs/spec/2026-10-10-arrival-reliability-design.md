# 설계 계획서 — 도착 신뢰도 기능 (AR-1 · AR-2 · AR-3)

상태: 초안 (승인 전에는 구현하지 않음) · 명세: [명세서](2026-10-10-arrival-reliability-spec.md)

## 1. 지금 구조에서 다시 쓰는 것

| 필요한 것 | 이미 있는 곳 | 쓰는 방법 |
|---|---|---|
| 열차별 30일 도착 지연 | `RailRepository.trainStats` — `rail.od_trips_real` 로 평균 · p90 · 정시율 | 같은 함수에서 **지연 값 배열**을 읽는 조회를 더한다(분포가 필요) |
| 지연 구간별 횟수 | `RailRepository.punctuality` — `count(*) FILTER (…)` 로 막대 6개 | 같은 스캔에 20 · 40 · 60 · 90 · 120분 이상 횟수를 더한다 |
| 전국 비교 | `RailRepository` 의 `rail.train_punctuality` 기간 요약 | 같은 구간 횟수를 더한다 |
| 여정 탐색 | `RailRouter`(CSA) · `RailJourneyService.plan`(후보 역 · 역까지 시간 · 오늘+내일 시간표 · 실제 시간표 재확인) | 거꾸로 찾는 `RailRouter.latest` 를 더하고, 후보 역 · 시간표 · 재확인은 그대로 쓴다 |
| 역까지 · 역에서 시간 | `RailJourneyService.accessTimes`(카카오 다중 길찾기 · 1km 미만 도보) | 그대로(출발 시각만 바꿔 부름) |
| 자동차 소요 | `KakaoMobilityClient.futureEta(…, departAt)` (20분 캐시 · 공유 예산) | 출발 시각을 바꿔 최대 4번 |
| 늦은 값 처리 | `TripService.part` · `pending`(캐시 안 함, 화면 재조회) | 같은 규칙 |
| 요청 한도 | `RateLimitInterceptor.BUCKETS` 의 `/api/v1/trip` 접두사 | `/api/v1/trip/arrival` 은 자동으로 `trip` 버킷 |
| 화면 조회 | `useApi`(재시도 상한 · 숨긴 탭 멈춤 · `pending` 재조회) | 새 훅 `useArrival` |

## 2. 데이터 조사 결과 (설계에 미친 영향)

개발 DB 에서 읽기 전용으로 쟀다(운행정보 기준일 10-03).

| 사실 | 값 | 설계에 미친 영향 |
|---|---|---|
| 20분 이상 지연은 드물다 | 전국 종착역 30일 27,494회 중 171회(0.62%) · 40분 이상 0.20% · 60분 이상 0.10%. 서울→대전 30일 2,953회 중 11회(0.37%) | 배상 통계 기본 기간 90일, **횟수 먼저** 표시(R14 · R15) |
| 열차당 30일 표본은 적다 | 서울→대전 142개 열차, 열차당 검증 운행 평균 20.8 · 중앙값 24, 10회 미만 8개 | 확률은 15회 미만이면 빈도만(R4). 꼬리 확률(95% 이상)은 근거가 약하다는 문구 |
| 카카오 예측과 고속도로 실측을 짝지을 수 있다 | 카카오 예측(`ana.kakao_eta`)이 길의 첫 영업소 → 마지막 영업소 좌표로 저장되어, 같은 출발 슬롯의 길 소요(`ts.road_corridor_tt`)와 짝 811개(16개 길·방향, 09-24 ~ 10-04) | 자동차 오차 모델이 가능하다(3단계) |
| 그 짝의 비율이 한쪽으로 치우쳤다 | 실측 ÷ 카카오: p10 0.92 · **중앙값 1.175** · p90 1.42 · p95 1.53 | 추석이 낀 기간이고, 길 소요가 '같은 시각 구간 합'이라 실제로 달린 시간이 아니다 → **정답 데이터(궤적 합)부터** 만든다(3.3절). 그 전에는 자동차 확률을 내지 않는다(D1-A) |
| 수서 발착 열차가 9월부터 없다 | 운행정보의 수서역 정차: 6월 24 · 7월 104 · 8월 92 · 9월 이후 0 | 범위 밖. 통합 뒤 출처 확인을 별도 작업으로 |
| 운행정보가 10-04 부터 비었다 | 운행정보 최신 10-03, 도로 10-04 22:55(스택이 꺼져 있던 기간) | 철도는 재수집(`make rail-backfill`)으로 채울 수 있다. 도로는 원천이 당일만 줘서 못 채운다 |

## 3. 계산 모델

### 3.1 기차 도착 확률 (AR-1)

새 순수 클래스 `domain/DelayDistribution` — 정렬한 도착 지연 배열(검증 운행만).

```
probWithin(m)   = count(d ≤ m) / n          // 경험적 누적분포(ECDF)
allWithin(m)    = m ≥ max(d)                // "관측된 n회 모두 기한 안" (R5)
```

새 순수 클래스 `domain/ArrivalOdds` — 여정 하나의 확률.

```
여정 legs L1 … Lk (실제 시간표 시각), 역에서 목적지까지 egress
margin_k = arriveBy − egress − L_k.planArr                  // 마지막 구간 여유(분)
slack_i  = L_{i+1}.planDep − L_i.planArr − BOARDING_BUFFER(5) // 환승 여유(분), i < k
P        = Π_{i<k} D_i.probWithin(slack_i) × D_k.probWithin(margin_k)
```

- 구간 지연은 서로 독립이라고 가정한다(같은 날 같은 노선 지연은 함께 커질 수 있어 환승 여정의 확률이 실제보다 높게 나올 수 있다 → 화면 문구 · 보정 검사로 확인).
- 다음 열차의 지연은 반영하지 않는다(보수적). 환승을 놓친 뒤 다음 열차로 가는 경우도 v1 에서는 '기한 실패'로 센다.
- 표시 규칙(R4 · R5 · R6)은 `ArrivalOdds` 의 결과 레코드가 가진다: `n`(구간 표본의 최솟값) · `within` · `percent`(n<15 이면 null) · `allObservedWithin`.
- 근거 기간은 판단 카드와 같은 '기준일까지 30일'. 기준일은 `RailService.referenceDate`(같은 요일 최근 운행일)를 그대로 쓴다.

데이터 조회 — `RailRepository.delaySamples(dep, arr, from, to, trains)`:

```sql
SELECT trn_no, array_agg(arr_delay_min::float ORDER BY arr_delay_min) AS delays
FROM rail.od_trips_real(:a, :b, :f, :t)
WHERE trn_no IN (:trains) AND arr_delay_min IS NOT NULL
GROUP BY trn_no
```

결과는 `(역 쌍, 기준일, 열차)` 키로 10분 캐시(`JsonCache`, 운행정보는 하루 세 번만 바뀜).

### 3.2 마지막 출발 시각 (AR-2)

**기차 — 거꾸로 찾는 CSA.** 새 순수 함수 `RailRouter.latest(sorted, origins, dests, deadline, transferSec, depCap)`:

```
τ(stn) = 그 역에서 출발해 기한 안에 도착할 수 있는 가장 늦은 출발 시각 (처음 -∞)
연결을 출발 시각 내림차순으로 훑는다 (c: u → v, dep, arr, trip):
  도달 가능 =  v 가 도착 후보이고 arr + egress(v) ≤ deadline
            또는 trip 을 이미 타고 있음(뒤 연결에서 표시)
            또는 arr + transferSec ≤ τ(v)
  도달 가능이면: τ(u) = max(τ(u), dep), trip 표시, 되짚기용으로 c 저장
결과: 출발 후보역 o 중 τ(o) − access(o) − 승차 여유 가 가장 늦고 depCap 보다 이른 여정
```

후보 여러 개는 `depCap` 을 직전 후보의 출발 − 1초로 낮춰 다시 부른다(최대 6번, 한 번에 연결 약 2만 개 · 밀리초 단위). 서비스 흐름(`RailJourneyService.latestPlan`, 새 메서드):

1. 후보 역 · 역까지 시간: 기존 `plan` 과 같다. 다만 출발 시각을 모르므로 **1차 추정 출발**(기한 − (직선거리 ÷ 100km/h + 60분), 지금보다 이르면 지금)로 역까지 시간을 구한다. 역에서 시간은 기한 기준.
2. 시간표: 기존 `timetable(오늘)`(오늘 + 내일). 기한은 지금 + 24시간 안이라 이 범위에 든다.
3. `latest` 로 후보를 출발이 늦은 순으로 뽑고, 기존 `toJourney` 와 같은 방식으로 **실제 시간표로 다시 확인**(승차 여유 · 최소 환승)한다.
4. 후보마다 3.1 로 확률을 매기고, 신뢰 수준 이상인 것 중 가장 늦은 것을 고른다. 없으면 확률이 가장 높은 후보(`meetsConfidence=false`).
5. 고른 여정의 실제 출발 시각이 1차 추정과 30분 넘게 다르면, 그 시각으로 역까지 시간을 다시 구해(카카오 캐시) 성립하는지 확인한다. 성립하지 않으면 다음 후보.

**자동차 1단계 — 카카오 예측 시각.** 순수 함수 `domain/LatestDeparture.search(T, deadline)`(T = 출발 시각 → 소요, 호출은 바깥에서 주입):

```
t ← deadline − T_guess (판단 카드의 지금 소요 · 없으면 직선거리 기반)
반복 (최대 4번 호출): s = T(t);  t + s ≤ deadline 이면 멈춤, 아니면 t ← t − ceil5(t + s − deadline)
멈춘 뒤 호출이 남으면 t + 5분을 한 번 더 확인(더 늦게 떠나도 되는지)
```

### 3.3 자동차 도착 확률 (3단계, 결정 D1)

정답 데이터가 먼저다. 지금 길 소요(`ts.road_corridor_tt`)는 **같은 슬롯의 구간 소요를 더한 값**이라, 정체가 커지거나 풀리는 동안 실제로 달린 시간과 다르다.

1. **궤적 합** — 출발 슬롯 t 에서 구간1 소요 s1(t), 구간2는 t + s1 슬롯의 소요 s2 … 를 이어 더한다(`ts.road_travel_time` 5분 슬롯). 수집기 분석 작업(`kakao_eta_eval`, 매일)이 `ana.kakao_eta` 의 출발 시각마다 궤적 합을 계산해 새 표 `ana.kakao_eta_eval`(V19)에 둔다. 먼저 확인할 것: 도로공사 슬롯 시각이 진입 기준인지 진출 기준인지(그에 따라 이어 붙이는 방향이 다르다).
2. **오차 분포** — 비율 R = 궤적 합 ÷ 카카오 예측. 시간대(0~6 · 6~10 · 10~16 · 16~20 · 20~24시) × 공휴일 여부로 나눠, 모든 길을 합쳐(풀링) 경험 분포를 만든다. 칸의 표본이 50 미만이면 그 칸은 쓰지 않는다.
3. **확률** — P(자동차가 기한 안) = P(R ≤ (기한 − t) ÷ T(t)). 마지막 출발 시각은 R 의 신뢰 수준 분위수 q_c 로 t + q_c × T(t) ≤ 기한.
4. **보정 검사** — 마지막 2주를 떼어 두고 80 · 90 · 95% 판단의 실제 적중률을 잰다. ±5%p 안일 때만 화면에 낸다. 고속도로 8개 길 밖의 경로에 쓰는 것은 외삽이라 "고속도로 8개 길에서 잰 카카오 예측 오차로 계산한 추정"이라고 밝힌다.

### 3.4 지연 배상 통계 (AR-3)

`RailRepository.punctuality` 의 같은 스캔에 더한다(추가 비용은 집계 5개):

```sql
count(*) FILTER (WHERE arr_delay_min >= 20)  AS ge20,
count(*) FILTER (WHERE arr_delay_min >= 40)  AS ge40,
count(*) FILTER (WHERE arr_delay_min >= 60)  AS ge60,
count(*) FILTER (WHERE arr_delay_min >= 90)  AS ge90,
count(*) FILTER (WHERE arr_delay_min >= 120) AS ge120
```

- 분모는 검증 운행(`count(arr_delay_min)`). 경계는 '이상'(약관: 20분 이상).
- 배상률표는 새 순수 클래스 `domain/CompensationRule` 에 버전 · 출처 · 구간으로 둔다. v1 에서는 구간 경계만 쓰고 배상률은 쓰지 않는다(결정 D2-A). 원문을 확인하면 배상률과 출처를 채우고 '예상 배상률'을 더한다.
- 기간 '보관 전체'는 운행정보 첫 날짜부터(지금 06-24). `punctuality` 의 366일 상한 안이다.

## 4. API

### 4.1 `GET /api/v1/trip/arrival` (새로)

| 매개변수 | 규칙 |
|---|---|
| `fromLat` `fromLon` `fromName` `fromStation?` `toLat` `toLon` `toName` `toStation?` | `/trip` 과 같다 |
| `arriveBy` | `yyyy-MM-ddTHH:mm` (KST). 지금 + 30분 ~ 지금 + 24시간, 5분 단위로 내림 |
| `confidence` | `0.8` · `0.9` · `0.95` (기본 `0.9`) |
| `accessMin?` | `/trip` 과 같다 |

응답(요약):

```json
{
  "arriveBy": "2026-10-11T14:00+09:00", "confidence": 0.9, "asOf": "…",
  "train": {
    "latestDepart": "…11:55", "meetsConfidence": true,
    "odds": { "percent": 95, "within": 23, "n": 24, "allObservedWithin": false, "basis": "최근 30일 실제 운행(기준일 …)" },
    "journey": { "…": "기존 Journey 형식" },
    "legRisks": [ { "trnNo": "117", "kind": "TRANSFER", "slackMin": 12, "within": 22, "n": 24 } ],
    "alternatives": [ { "latestDepart": "…12:20", "odds": { "percent": 70, "within": 14, "n": 20 } } ]
  },
  "car": {
    "latestDepart": "…11:20", "durationSec": 9000, "odds": null,
    "basis": "카카오 미래 운행 정보(예측). 예측 오차 근거가 준비되지 않아 확률은 내지 않습니다", "calls": 3
  },
  "summary": "기차로 가면 35분 늦게 떠나도 됩니다.",
  "assumptions": ["구간 지연은 서로 독립", "환승 시 승차 여유 5분", "운행 취소는 반영되지 않음", "역까지 시간은 카카오 예측"],
  "pending": false, "cache": "MISS"
}
```

- 오류: 기한 범위 밖 · 신뢰 수준 · 좌표 → 400 `VALIDATION_ERROR`(기존 오류 규약). 과부하 → 503(기존).
- 캐시 키: `arrival:v1:{출발}:{도착}:{역 지정}:{arriveBy}:{confidence}:{accessMin}`, 60초(판단 카드와 같은 `nowCacheSeconds`). `pending` 이면 캐시하지 않는다.

### 4.2 `GET /api/v1/rail/od/punctuality` (필드 추가 — 하위 호환)

- `summary.delayBands`, `items[].delayBands`, `nationwideExact.delayBands`: `{ "verified": 72, "ge20": 1, "ge40": 0, "ge60": 0, "ge90": 0, "ge120": 0 }`.
- `rules` 에 `"DB-v1": "배상 기준 시간(20·40·60·90·120분 이상) — 도착역 계획 도착 대비"` 를 더한다.

## 5. 계층별 변경 (ADR-025 지킴)

| 계층 | 파일 | 내용 |
|---|---|---|
| domain(순수) | `DelayDistribution` · `ArrivalOdds` · `LatestDeparture` · `CompensationRule` (새로) · `RailRouter.latest` (추가) | 계산만. 스프링 · DB 없음 |
| rail/data | `RailRepository.delaySamples` (새로) · `punctuality` · 전국 요약 (집계 추가) | SQL 은 여기만 |
| rail/app | `RailService.delaySamples` (캐시) · `punctuality` 매핑 | |
| rail/model | `RailDtos.DelayBands` (새로) · `Summary` · `PunctualityItem` 에 필드 추가 | |
| trip/app | `ArrivalService` (새로) — 기차(`RailJourneyService.latestPlan`) · 자동차(`futureEta` + `LatestDeparture`) 동시 실행, 마감 공유 | trip → rail 의 app 만 부른다 |
| trip/app | `RailJourneyService.latestPlan` (새로) — 후보 역 · 시간표 · 재확인은 기존 코드를 함께 쓰도록 작은 메서드로 나눈다 | 기존 `plan` 동작은 그대로 |
| trip/model | `ArrivalDtos` (새로) | |
| trip/web | `TripController.arrival` (새로) | 입력 검증 |
| web | `lib/arrival.ts`(새로: 확률 문구 규칙 · 시각 선택지 · 주소 매개변수) · `lib/api/client.ts`(`api.arrival`) · `lib/hooks/useArrival.ts` · `components/trip/ArrivalCards.tsx` · `pages/index.tsx`(모드 전환) · `pages/rail/index.tsx` · `components/rail/DelayBands.tsx` | 화면은 그리기만 |

새 의존성 없음. 새 외부 API 없음. DB 마이그레이션: 1 · 2단계 없음, 3단계에 `V19__kakao_eta_eval.sql`(새 표 하나, 기존 표 안 건드림).

## 6. 화면

**판단 화면(`/`)**

- 출발 시점 선택 옆에 모드 라디오 그룹: `출발 시각 기준` · `도착 시각 기준`. 도착 기준이면 `오늘/내일` + 시각(5분 단위 선택 상자) + 신뢰 수준(80 · 90 · 95%).
- 주소: `?by=arrive&d=0|1&at=1400&c=90` — 새로 고침 · 공유 시 같은 결과.
- 결과 카드 두 장: "늦어도 11:55 출발" · 확률 막대와 숫자 · 빈도("24회 중 23회") · 표본 기간. 신뢰 수준 미달이면 회색 대신 문구로("90% 를 만족하는 열차 없음") — 색만으로 구분하지 않는다.
- 판단 근거 영역에 가정 목록(독립 가정 · 승차 여유 5분 · 운행 취소 미반영 · 역까지 시간은 카카오 예측).
- 결과 칸은 처음부터 높이를 잡는다(QA-04 · 09 의 레이아웃 이동 재발 방지).

**철도 분석(`/rail`)**

- 새 영역 "지연 배상 기준을 넘긴 운행": 역 쌍 전체와 전국의 20 · 40 · 60 · 90 · 120분 이상 횟수 표 + 원인 문구(R18).
- 열차별 표에 `20분↑` 칸("1/72"), 기간 선택에 `보관 전체` 추가.

## 7. 검증 계획

| 층 | 시험 | 지키는 것 |
|---|---|---|
| domain 단위(JUnit) | `DelayDistributionTest` — 경계(=, <, >), 빈 배열, 최대값 초과 | R2 · R5 · R6 |
| | `ArrivalOddsTest` — 단일 구간, 환승 곱(0.9 × 0.8), 15회 경계 | R3 · R4, A4 · A6 |
| | `RailRouterTest.latest` — 손으로 만든 시간표: 직통 · 환승 · 기한 직전 · 자정 넘김 · depCap 으로 다음 후보 | R8, A3 · A7 |
| | `LatestDepartureTest` — 가짜 T(t)로 호출 수 ≤ 4, 단조가 아닌 T 에서도 기한을 넘기지 않음 | R10 |
| rail/data 통합(Testcontainers) | `delaySamples` · 배상 구간(0 · 19 · 20 · 45 · 61 · 125분) | A11 · A12 |
| API 통합(`ApiIT`) | 기한 범위 · 신뢰 수준 400, 정상 응답 형식, 캐시 적중 시 카카오 호출 수 불변, punctuality 기존 필드 유지 | A1 · A2 · A9 · A13 |
| 웹 단위(node:test) | `lib/arrival` — 문구 규칙(빈도 · 퍼센트 · 모두 기한 안), 주소 매개변수 왕복, 시각 선택지(지금 + 30분 ~ 24시간) | A4 · A5 · A10 |
| E2E(Playwright) | 도착 시각 기준 전환 · 주소 유지 · 키보드 조작 · 레이아웃 이동 < 0.1 · 철도 표 `20분↑` 칸 | A10 · A14 |
| 실데이터 | 서울→대전 · 서울→부산 등 5개 역 쌍으로 결과를 손으로 대조(시간표 · 지연 기록) | 계산 정확성 |
| **보정 검사** | 운행정보 06-24 ~ 지금에서, 날짜 d 마다 [d−30, d−1] 분포로 d 의 운행을 예측 → 80 · 90 · 95% 판단의 실제 적중률 · Brier 점수. 스크립트(`tools/arrival_calibration.py`, DB 읽기만)로 시작, 결과는 검증 기록과 ADR 에 | 출시 조건(±5%p) |

## 8. 단계 · PR 계획

| 단계 | PR | 커밋(주제별) | 규모 |
|---|---|---|---|
| 1 | `feat/delay-bands` — AR-3 | ① `CompensationRule` + 시험 ② 저장소 집계 + 통합 시험(실패 먼저) ③ DTO · 서비스 ④ 화면 · E2E ⑤ 문서(README API · DATA-PROVENANCE · 시험 수) | 작음 |
| 2 | `feat/arrival-train` — AR-1 · AR-2 기차 + 자동차 예측 시각 | ① `DelayDistribution` · `ArrivalOdds` ② `RailRouter.latest` ③ `delaySamples` ④ `RailJourneyService.latestPlan` ⑤ `ArrivalService` · 컨트롤러 · `ApiIT` ⑥ `LatestDeparture` + 자동차 ⑦ 화면 · 훅 · E2E ⑧ 보정 검사 스크립트 + 결과 ⑨ ADR-029 · 문서 | 큼 |
| 3 | `feat/arrival-car-odds` — 자동차 확률(결정 D1-A 일 때) | ① 슬롯 시각 의미 확인 → 궤적 합 순수 함수 + 시험 ② V19 + 수집기 작업 ③ 오차 분포 · 보정 검사 ④ 통과 시 API · 화면 | 중간 |
| 선택 | `feat/arrival-calibration-page` | 보정 결과를 매일 계산해 예측 성능 화면에 | 작음 |

순서는 1 → 2 → 3. 1단계는 다른 단계와 독립이라 먼저 병합할 수 있다. 각 PR 은 브랜치 → PR(한국어 본문) → `ci passed`. 결함을 찾으면 실패하는 시험부터.

## 9. 위험과 대응

| 위험 | 영향 | 대응 |
|---|---|---|
| 열차당 표본이 적다(중앙값 24) | 꼬리 확률 · 배상 비율이 불안정 | 15회 미만 빈도만, 배상 통계 기본 90일, 횟수 먼저 |
| 구간 지연 독립 가정 | 환승 여정 확률이 높게 나올 수 있다 | 보정 검사를 환승 여정만 따로도 계산, 어긋나면 상관을 반영하거나 환승 여정에 경고 |
| 운행 취소 미관측 | 확률이 실제보다 높다 | 화면 문구. 취소 데이터 출처를 찾으면 반영 |
| 기간 내 비정상 상황(명절 · 사고) | 분포가 바뀐다 | 표본 기간을 함께 표시. v2 에서 평일 · 주말 · 공휴일 나누기 검토 |
| 역까지 시간의 불확실성 | 마지막 출발 시각이 빠듯할 수 있다 | v1 은 카카오 값 그대로 + 문구. 3단계 오차 모델을 역까지에도 쓸지 검토 |
| 카카오 호출 증가 | 일일 예산(5,000) 소진 | 새 조합당 최대 14건 · 20분 캐시 · `trip` 버킷. 예산 화면으로 관찰 |
| 자동차 정답 데이터 | 오차 모델이 틀릴 수 있다 | 궤적 합 · 보정 검사 통과 전에는 확률을 내지 않음(D1-A) |
| 배상률표 출처 불일치 | 잘못된 금액 안내 | 원문 확인 전에는 비율을 쓰지 않음(D2-A) |
| 수서 발착 열차 누락 | 그 구간 사용자가 결과를 못 봄 | 범위 밖 명시 · 출처 확인을 별도 작업으로 |

## 10. 문서

- ADR-029: 도착 확률 모델(경험적 분포 · 독립 가정 · 표시 규칙 · 보정 검사 기준) · 배상 구간(DB-v1) · 자동차 확률 보류 이유.
- DATA-PROVENANCE: 도착 확률 · 마지막 출발 시각 · 배상 기준 넘김 비율 행.
- README: 5장 API 표 · 주요 기능 · 7장 시험 수. 실데이터로 결함을 찾으면 VERIFICATION 에 행.
