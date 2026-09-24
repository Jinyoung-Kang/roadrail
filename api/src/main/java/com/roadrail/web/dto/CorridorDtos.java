package com.roadrail.web.dto;

import java.util.List;
import java.util.Map;

public final class CorridorDtos {
    private CorridorDtos() {}

    public record Point(String code, String name, Double lat, Double lon) {}

    public record RoadPath(int segments, double distanceKm, List<Point> units) {}

    public record RailPair(Point dep, Point arr) {}

    public record EnvPoint(String role, String name, double lat, double lon, int nx, int ny, String sido) {}

    public record Corridor(String id, String name, String originCity, String destCity,
                           Map<String, RoadPath> road, Map<String, RailPair> rail, List<EnvPoint> env) {}
}
