package com.roadrail.domain;

import java.util.List;

/**
 * 여정 하나가 도착 기한 안에 도착할 확률 (규칙 AR-v1, ADR-029).
 * <pre>
 *   마지막 구간: 도착 지연 ≤ 여유(= 기한 − 역에서 시간 − 계획 도착)
 *   환승 구간:   앞 열차 도착 지연 ≤ 환승 여유(= 다음 열차 계획 출발 − 앞 열차 계획 도착 − 승차 여유)
 *   확률 = 구간 확률의 곱 — 구간 지연이 서로 독립이라는 가정(같은 날 지연은 함께 커질 수 있어 환승 여정은 높게 나올 수 있다).
 *   다음 열차의 지연은 반영하지 않는다(보수적). 운행 취소는 운행정보에 없어 반영되지 않는다.
 * </pre>
 */
public final class ArrivalOdds {
    private ArrivalOdds() {}

    /** 검증 운행이 이보다 적으면 퍼센트를 내지 않고 빈도만 (명세 R4) */
    public static final int MIN_SAMPLES_FOR_PERCENT = 15;

    /** 구간 하나 — 그 열차 · 역 쌍의 지연 분포와 허용 지연(분) */
    public record Step(DelayDistribution dist, double allowanceMin) {}

    /**
     * @param probability  구간 확률의 곱 (모르면 NaN)
     * @param percent      화면용 퍼센트(내림) — 표본 부족 · 모두 기한 안 · 모름이면 null
     * @param within       기한 안 운행 수 — 구간이 하나일 때만(환승 여정은 null)
     * @param n            구간 표본의 최솟값
     * @param allObservedWithin 모든 구간에서 관측된 모든 운행이 허용 지연 안
     */
    public record Odds(double probability, Integer percent, Integer within, int n, boolean allObservedWithin) {
        public boolean known() { return !Double.isNaN(probability); }

        /** 신뢰 수준을 만족 — 검증 운행이 15회 미만이면 근거가 약해 만족으로 치지 않는다(10회 모두 기한 안이어도 95% 신뢰 하한은 약 72%) */
        public boolean meets(double confidence) { return known() && n >= MIN_SAMPLES_FOR_PERCENT && probability + 1e-9 >= confidence; }
    }

    public static Odds of(List<Step> steps) {
        if (steps.isEmpty()) return new Odds(Double.NaN, null, null, 0, false);
        double p = 1;
        int n = Integer.MAX_VALUE;
        boolean all = true;
        for (Step s : steps) {
            if (s.dist().n() == 0) return new Odds(Double.NaN, null, null, 0, false);   // 기록이 없으면 확률을 비운다
            p *= s.dist().probWithin(s.allowanceMin());
            n = Math.min(n, s.dist().n());
            all &= s.dist().allWithin(s.allowanceMin());
        }
        Integer within = steps.size() == 1 ? steps.getFirst().dist().countWithin(steps.getFirst().allowanceMin()) : null;
        Integer percent = n < MIN_SAMPLES_FOR_PERCENT || all ? null : (int) Math.floor(p * 100 + 1e-9);
        return new Odds(p, percent, within, n, all);
    }
}
