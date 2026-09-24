package com.roadrail.domain;

import com.roadrail.common.Times;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 통행시간 예측 모델 (6-2). collector 의 roadrail/analytics/forecast.py 와 같은 식이며
 * fixtures/forecast_cases.json 골든 케이스를 두 언어의 테스트가 함께 검증한다 (언어 간 계약).
 *
 * <pre>
 * M0 기준선      = p50(dow(target), slot(target)); n &lt; 4 이면 전체 요일(dow=0) 기준선
 * M1 기준선+편차 = M0(target) + (current − M0(currentSlot)) × e^(−h/τ)
 * 지속           = current
 * </pre>
 */
public final class ForecastModels {
    public static final int MIN_N = 4;
    public static final String VERSION = "F-v1";

    public record Key(int dow, int slot) {}

    public record Value(int p50, int n) {}

    public record Forecast(Integer m0, Integer m1, int persistence) {}

    private ForecastModels() {}

    public static Integer m0(Map<Key, Value> baseline, OffsetDateTime target) {
        Value v = baseline.get(new Key(Times.isoDow(target), Times.slotIdx(target)));
        if (v != null && v.n() >= MIN_N) return v.p50();
        Value alt = baseline.get(new Key(0, Times.slotIdx(target)));
        if (alt != null && alt.n() >= MIN_N) return alt.p50();
        if (v != null) return v.p50();
        return alt == null ? null : alt.p50();
    }

    /** M0 가 실제로 사용한 기준선의 표본 수 (없으면 0) — 화면에 함께 표시 (NFR-06) */
    public static int m0N(Map<Key, Value> baseline, OffsetDateTime target) {
        Value v = baseline.get(new Key(Times.isoDow(target), Times.slotIdx(target)));
        if (v != null && v.n() >= MIN_N) return v.n();
        Value alt = baseline.get(new Key(0, Times.slotIdx(target)));
        if (alt != null && alt.n() >= MIN_N) return alt.n();
        if (v != null) return v.n();
        return alt == null ? 0 : alt.n();
    }

    /** 비교(편차 %)에 쓸 기준선 — 요일 기준선 n ≥ 4, 아니면 전체 요일 n ≥ 4. 둘 다 부족하면 null (FR-402 '비교 불가') */
    public static Value comparable(Map<Key, Value> baseline, OffsetDateTime t) {
        Value v = baseline.get(new Key(Times.isoDow(t), Times.slotIdx(t)));
        if (v != null && v.n() >= MIN_N) return v;
        Value alt = baseline.get(new Key(0, Times.slotIdx(t)));
        return alt != null && alt.n() >= MIN_N ? alt : null;
    }

    public static String label(String model) {
        return switch (model) {
            case "M0" -> "M0(기준선)";
            case "M1" -> "M1(기준선+편차)";
            case "persistence" -> "지속(최근 관측 유지)";
            default -> model;
        };
    }

    public static Integer m1(Map<Key, Value> baseline, OffsetDateTime currentSlot, int currentSec,
                             OffsetDateTime target, double tauMin) {
        Integer bTarget = m0(baseline, target);
        Integer bNow = m0(baseline, currentSlot);
        if (bTarget == null || bNow == null) return null;
        double h = Math.max(Duration.between(currentSlot, target).toSeconds() / 60.0, 0.0);
        return (int) Math.round(bTarget + (currentSec - bNow) * Math.exp(-h / tauMin));
    }

    public static Forecast predictAll(Map<Key, Value> baseline, OffsetDateTime currentSlot, int currentSec,
                                      OffsetDateTime target, double tauMin) {
        return new Forecast(m0(baseline, target), m1(baseline, currentSlot, currentSec, target, tauMin), currentSec);
    }
}
