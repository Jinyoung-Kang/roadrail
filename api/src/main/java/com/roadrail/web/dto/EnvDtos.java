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

    public record Incident(OffsetDateTime sentAt, String typeCode, String typeName, String routeName, String direction,
                           String process, String content, List<String> corridorIds) {}

    public record Incidents(String corridorId, OffsetDateTime since, List<Incident> items, String note) {}
}
