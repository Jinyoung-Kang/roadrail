package com.roadrail.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class EnvDtos {
    private EnvDtos() {}

    public record WeatherHour(OffsetDateTime at, Integer tmp, Integer pop, String pty, String sky) {}

    public record Air(OffsetDateTime dataTime, Integer pm10, Integer pm25, Integer pm25Grade, Integer khaiGrade,
                      int stations) {}

    public record PointEnv(String role, String name, String sido, int nx, int ny, OffsetDateTime baseAt,
                           List<WeatherHour> hourly, Air air) {}

    public record Env(String corridorId, List<PointEnv> points, String note) {}

    /**
     * 도로공사 실시간 문자 안내. lat · lon · pointName 은 응답에 있을 때만(없으면 null — 위치를 추정하지 않음).
     * routeKm = 자동차 경로와의 거리(판단 카드에서 안내 좌표가 경로 2km 안일 때만), lastSeenAt = 마지막으로 목록에서 본 시각.
     */
    public record Incident(OffsetDateTime sentAt, String typeCode, String typeName, String routeName, String direction,
                           String process, String content, List<String> corridorIds, Double lat, Double lon,
                           String pointName, OffsetDateTime lastSeenAt, Double routeKm) {
        public Incident withRouteKm(Double km) {
            return new Incident(sentAt, typeCode, typeName, routeName, direction, process, content, corridorIds, lat, lon,
                    pointName, lastSeenAt, km);
        }
    }

    public record Incidents(String corridorId, OffsetDateTime since, List<Incident> items, String note) {}
}
