package com.roadrail.domain;

import java.util.Arrays;
import java.util.Collection;

/**
 * 한 열차 · 한 역 쌍의 도착 지연(분) 경험 분포 — 검증 운행(계획 시각을 아는 운행)만. 도착 확률(AR-1)의 근거.
 * 확률 = (지연 ≤ 여유)인 운행 수 ÷ 운행 수 (경험적 누적분포, ECDF). 과거 빈도이지 보장이 아니다.
 */
public final class DelayDistribution {
    private final double[] sorted;

    private DelayDistribution(double[] sorted) {
        this.sorted = sorted;
    }

    public static DelayDistribution of(Collection<? extends Number> delays) {
        double[] a = delays.stream().filter(x -> x != null && Double.isFinite(x.doubleValue())).mapToDouble(Number::doubleValue).toArray();
        Arrays.sort(a);
        return new DelayDistribution(a);
    }

    public static DelayDistribution of(double... delays) {
        double[] a = Arrays.stream(delays).filter(Double::isFinite).toArray();
        Arrays.sort(a);
        return new DelayDistribution(a);
    }

    public int n() { return sorted.length; }

    /** 관측된 가장 큰 지연 (표본이 없으면 NaN) */
    public double max() { return sorted.length == 0 ? Double.NaN : sorted[sorted.length - 1]; }

    /** 지연 ≤ marginMin 인 운행 수 */
    public int countWithin(double marginMin) {
        int lo = 0, hi = sorted.length;   // 첫 번째로 margin 보다 큰 위치
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= marginMin) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** 지연 ≤ marginMin 일 확률 (표본이 없으면 NaN — 짐작하지 않는다) */
    public double probWithin(double marginMin) {
        return sorted.length == 0 ? Double.NaN : (double) countWithin(marginMin) / sorted.length;
    }

    /** 여유가 관측된 모든 지연 이상 — "100%" 대신 "관측된 n회 모두 기한 안"으로 쓴다 */
    public boolean allWithin(double marginMin) { return sorted.length > 0 && marginMin >= sorted[sorted.length - 1]; }
}
