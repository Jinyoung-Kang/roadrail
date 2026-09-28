package com.roadrail.service;

import com.roadrail.web.dto.EnvDtos.Incident;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 돌발 안내 좌표 ↔ 자동차 경로 거리 (경로 2km 안이면 '경로 위') */
class IncidentRouteTest {
    // 남북으로 곧은 경로 (경도 127.0, 위도 36.0 → 36.2)
    private static final List<double[]> PATH = List.of(new double[]{36.0, 127.0}, new double[]{36.1, 127.0}, new double[]{36.2, 127.0});

    @Test
    void pointOnPathIsZero() {
        assertThat(EnvService.distanceToPathKm(36.05, 127.0, PATH)).isCloseTo(0.0, within(0.01));
    }

    @Test
    void sidewaysDistanceUsesLatitudeCorrectedLongitude() {
        // 경도 0.01° 동쪽 = 111.32 × cos(36.1°) × 0.01 ≈ 0.90km
        assertThat(EnvService.distanceToPathKm(36.1, 127.01, PATH)).isCloseTo(0.90, within(0.01));
        assertThat(EnvService.distanceToPathKm(36.1, 127.03, PATH)).isGreaterThan(EnvService.ROUTE_KM);
    }

    @Test
    void beyondEndMeasuresToEndpoint() {
        // 경로 끝(36.2)에서 북쪽 0.01° ≈ 1.1km
        assertThat(EnvService.distanceToPathKm(36.21, 127.0, PATH)).isCloseTo(1.11, within(0.01));
    }

    static Incident incident(String source, Double lat, Double lon, String type) {
        return new Incident(java.time.OffsetDateTime.parse("2026-09-28T17:30:00+09:00"), source.equals("UTIC") ? "U1" : "1", type,
                "경부고속도로", null, null, "[" + type + "]", List.of(), lat, lon, null, null, 0.1, source, null, null);
    }

    @Test
    void uticNextToAnExpresswayMessageIsTheSameIncident() {
        // 고속도로 사고는 도로공사 문자와 UTIC 에 모두 나온다 — 0.5km 안이면 도로공사 문자 하나만
        var ex = incident("EX", 36.100, 127.000, "사고");
        var dup = incident("UTIC", 36.103, 127.000, "사고");       // 약 0.33km
        var city = incident("UTIC", 36.150, 127.000, "공사");      // 약 5.6km — 다른 돌발
        var noCoord = incident("UTIC", null, null, "통제");           // 좌표가 없으면 겹치는지 알 수 없어 남긴다
        assertThat(EnvService.withoutDuplicates(List.of(ex, dup, city))).containsExactly(ex, city);
        assertThat(EnvService.withoutDuplicates(List.of(dup, city))).containsExactly(dup, city);   // 도로공사 문자가 없으면 그대로
        assertThat(EnvService.withoutDuplicates(List.of(ex, noCoord))).containsExactly(ex, noCoord);
    }

    @Test
    void cityIncidentsMustBeOnTheRouteItself() {
        // 도시 도로는 촘촘해 2km 안이면 옆 도로까지 잡힌다 — UTIC 는 0.5km, 고속도로 문자는 그대로 2km
        assertThat(EnvService.routeLimitKm("UTIC")).isEqualTo(0.5);
        assertThat(EnvService.routeLimitKm("EX")).isEqualTo(2.0);
        assertThat(EnvService.routeLimitKm(null)).isEqualTo(2.0);
        double sideRoad = EnvService.distanceToPathKm(36.1, 127.012, PATH);   // 경로에서 동쪽 약 1.1km
        assertThat(sideRoad).isGreaterThan(EnvService.routeLimitKm("UTIC")).isLessThan(EnvService.routeLimitKm("EX"));
    }
}
