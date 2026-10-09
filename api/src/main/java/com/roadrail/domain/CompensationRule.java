package com.roadrail.domain;

import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 지연 배상 기준 시간 (규칙 DB-v1, ADR-029). 코레일 여객운송약관: 승차권의 도착역 도착 시각보다 20분 이상 늦으면 배상 대상이 될 수 있다.
 * 배상률표는 출처가 엇갈려(60분 이상 50% 하나 ↔ 통합 뒤 50 · 75 · 100%) 원문을 확인하기 전까지 구간 경계만 쓴다 — 금액 · 비율은 내지 않는다.
 * 실제 배상 여부는 원인(천재지변 · 응급 구호 등 제외 사유)에 달려 있고 운행정보에는 원인이 없다.
 */
public final class CompensationRule {
    private CompensationRule() {}

    public static final String VERSION = "DB-v1";
    /** 배상 기준 시간(분) — '이상' */
    public static final int[] THRESHOLDS_MIN = {20, 40, 60, 90, 120};
    public static final String DESCRIPTION = "배상 기준 시간(20·40·60·90·120분 이상) — 도착역 계획 도착 대비. "
            + "배상 여부는 지연 원인에 따라 달라 이 값과 다를 수 있음";

    /** SQL 집계 식: count(*) FILTER (WHERE {col} >= 20) AS ge20, … — 구간 경계를 한 곳(THRESHOLDS_MIN)에서만 정하려고 */
    public static String sqlCounts(String col) {
        return IntStream.of(THRESHOLDS_MIN).mapToObj(t -> "count(*) FILTER (WHERE " + col + " >= " + t + ") AS ge" + t)
                .collect(Collectors.joining(", "));
    }

    /** 지연 값(검증 운행만, null 은 뺀다)에서 구간별 '이상' 횟수 */
    public static int[] counts(Collection<Double> delays) {
        int[] out = new int[THRESHOLDS_MIN.length];
        for (Double d : delays) {
            if (d == null) continue;
            for (int i = 0; i < THRESHOLDS_MIN.length; i++) if (d >= THRESHOLDS_MIN[i]) out[i]++;
        }
        return out;
    }
}
