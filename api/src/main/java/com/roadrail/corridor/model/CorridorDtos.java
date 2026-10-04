package com.roadrail.corridor.model;

import java.util.List;
import java.util.Map;

public final class CorridorDtos {
    private CorridorDtos() {}

    public record Point(String code, String name, Double lat, Double lon) {}

    /** 출발 · 도착 지점에 맞는 길과 방향 (판단 카드의 길 매칭) */
    public record CorridorMatch(String corridorId, String direction) {}

    public record RoadPath(int segments, double distanceKm, List<Point> units) {}

    public record RailPair(Point dep, Point arr) {}

    public record EnvPoint(String role, String name, double lat, double lon, int nx, int ny, String sido) {}

    public record Corridor(String id, String name, String originCity, String destCity,
                           Map<String, RoadPath> road, Map<String, RailPair> rail, List<EnvPoint> env) {}
}
