package com.roadrail.service;

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
}
