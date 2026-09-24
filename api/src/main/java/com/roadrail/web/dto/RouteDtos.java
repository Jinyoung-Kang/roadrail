package com.roadrail.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 도로 분석 — 전국 임의의 두 지점 (카카오 경로 기반) */
public final class RouteDtos {
    private RouteDtos() {}

    public record Share(String type, int distanceM, int durationSec, double share) {}

    /** 같은 이름의 연속 구간을 합친 도로 */
    public record RoadRun(String name, String type, int distanceM, int durationSec, Double speedKmh, String traffic) {}

    public record RouteSummary(String label, Integer durationSec, Integer distanceM,
                               List<double[]> path, List<Share> byType, List<RoadRun> roads, List<RoadRun> slow) {}

    public record ProfilePoint(OffsetDateTime departAt, int offsetMin, Integer durationSec) {}

    public record Monitored(String corridorId, String name, String direction) {}

    public record Analysis(TripDtos.Place from, TripDtos.Place to, double straightKm, OffsetDateTime departAt,
                           RouteSummary recommended, RouteSummary avoidMotorway, List<ProfilePoint> profile,
                           ProfilePoint bestDeparture, Monitored monitored, String note) {}
}
