package com.roadrail.trip.model;

import java.time.OffsetDateTime;
import java.util.List;

/** 도착 시각 기준 판단 (AR-1 · AR-2) — "몇 시까지 도착하려면 늦어도 언제 떠나야 하나" */
public final class ArrivalDtos {
    private ArrivalDtos() {}

    /**
     * 기한 안에 도착할 확률 (규칙 AR-v1). percent 는 검증 운행이 15회 이상이고 모두 기한 안이 아닐 때만 —
     * 그 밖에는 within/n(빈도)만 쓴다. within 은 구간이 하나일 때만(환승 여정은 구간마다 legRisks).
     */
    public record Odds(Integer percent, Integer within, int n, boolean allObservedWithin, String basis) {}

    /** 구간별 위험 — TRANSFER: 이 열차가 환승 여유 안에 올 빈도, ARRIVAL: 마지막 열차가 기한 안에 올 빈도 */
    public record LegRisk(String trnNo, String kind, int allowanceMin, int within, int n) {}

    public record Alternative(OffsetDateTime latestDepart, OffsetDateTime arriveAt, int transfers, Odds odds) {}

    /** 기차 — 신뢰 수준을 만족하는 가장 늦은 출발 (없으면 확률이 가장 높은 후보, meetsConfidence=false) */
    public record Train(OffsetDateTime latestDepart, boolean meetsConfidence, Odds odds, JourneyDtos.Journey journey,
                        List<LegRisk> legRisks, List<Alternative> alternatives, String referenceDate, String note) {}

    /** 자동차 — 카카오 미래 운행 정보(예측)로 기한 안에 도착하는 가장 늦은 출발(10분 단위). 확률은 내지 않는다(결정 D1-A) */
    public record Car(OffsetDateTime latestDepart, Integer durationSec, boolean feasible, int calls, String basis, String note) {}

    public record Arrival(TripDtos.Place from, TripDtos.Place to, OffsetDateTime arriveBy, double confidence, OffsetDateTime asOf,
                          Train train, Car car, String summary, List<String> assumptions, boolean pending, String cache) {
        public Arrival withCache(String c) {
            return new Arrival(from, to, arriveBy, confidence, asOf, train, car, summary, assumptions, pending, c);
        }
    }
}
