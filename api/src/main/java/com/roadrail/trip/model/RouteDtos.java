package com.roadrail.trip.model;

import com.roadrail.domain.RouteGeometry;

import java.time.OffsetDateTime;
import java.util.List;

/** 도로 분석 — 전국 임의의 두 지점 (카카오 경로 기반) */
public final class RouteDtos {
    private RouteDtos() {}

    public record Share(String type, int distanceM, int durationSec, double share) {}

    /** 같은 이름의 연속 구간을 합친 도로 */
    /** traffic = 이 도로에서 가장 나쁜 소통, trafficM = 그 소통인 길이(m) — 긴 도로 전체가 '정체'로 보이지 않게 */
    public record RoadRun(String name, String type, int distanceM, int durationSec, Double speedKmh, String traffic, int trafficM) {}

    public record RouteSummary(String label, Integer durationSec, Integer distanceM, List<double[]> path,
                               List<RouteGeometry.TrafficRun> traffic, List<Share> byType, List<RoadRun> roads, List<RoadRun> slow) {}

    public record ProfilePoint(OffsetDateTime departAt, int offsetMin, Integer durationSec) {}

    public record Monitored(String corridorId, String name, String direction) {}

    public record Analysis(TripDtos.Place from, TripDtos.Place to, double straightKm, OffsetDateTime departAt,
                           RouteSummary recommended, RouteSummary avoidMotorway, List<ProfilePoint> profile,
                           ProfilePoint bestDeparture, Monitored monitored, String note) {}
}
