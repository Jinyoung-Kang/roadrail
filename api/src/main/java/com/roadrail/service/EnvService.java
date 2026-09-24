package com.roadrail.service;

import com.roadrail.common.Times;
import com.roadrail.web.dto.EnvDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class EnvService {
    private final JdbcClient jdbc;

    public EnvService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Env env(String cid, int hours) {
        List<PointEnv> pts = new ArrayList<>();
        var points = jdbc.sql("""
                SELECT role, name, sido_name, nx, ny FROM ref.corridor_env_point WHERE corridor_id = :c ORDER BY role DESC""")
                .param("c", cid).query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5)}).list();
        OffsetDateTime from = Times.now().withMinute(0).withSecond(0);
        for (Object[] p : points) {
            int nx = (int) p[3], ny = (int) p[4];
            OffsetDateTime base = jdbc.sql("SELECT max(base_at) FROM env.weather_fcst WHERE nx = :x AND ny = :y")
                    .param("x", nx).param("y", ny).query(OffsetDateTime.class).optional().orElse(null);
            List<WeatherHour> hourly = base == null ? List.of() : hourly(base, nx, ny, from, from.plusHours(hours));
            pts.add(new PointEnv((String) p[0], (String) p[1], (String) p[2], nx, ny, Times.kst(base), hourly, air((String) p[2])));
        }
        return new Env(cid, pts, "단기예보(발표 시각 baseAt 기준)와 시도 내 측정소의 최근 측정값 중앙값. 값이 없으면 null.");
    }

    public List<WeatherHour> hourly(OffsetDateTime base, int nx, int ny, OffsetDateTime from, OffsetDateTime to) {
        Map<OffsetDateTime, Map<String, String>> byHour = new TreeMap<>();
        jdbc.sql("""
                SELECT fcst_at, category, value FROM env.weather_fcst
                WHERE base_at = :b AND nx = :x AND ny = :y AND fcst_at >= :f AND fcst_at < :t ORDER BY fcst_at""")
                .param("b", base).param("x", nx).param("y", ny).param("f", from).param("t", to)
                .query(rs -> {
                    byHour.computeIfAbsent(Times.kst(rs.getObject(1, OffsetDateTime.class)), k -> new HashMap<>())
                            .put(rs.getString(2), rs.getString(3));
                });
        List<WeatherHour> out = new ArrayList<>();
        byHour.forEach((t, m) -> out.add(new WeatherHour(t, RoadService.parseInt(m.get("TMP")), RoadService.parseInt(m.get("POP")),
                RoadService.ptyName(m.get("PTY")), skyName(m.get("SKY")))));
        return out;
    }

    /** 목표 시각의 예보 (가장 최근 발표, 해당 시각 이하의 가장 가까운 예보 시각) */
    public Optional<WeatherHour> at(int nx, int ny, OffsetDateTime target) {
        OffsetDateTime base = jdbc.sql("SELECT max(base_at) FROM env.weather_fcst WHERE nx = :x AND ny = :y")
                .param("x", nx).param("y", ny).query(OffsetDateTime.class).optional().orElse(null);
        if (base == null) return Optional.empty();
        OffsetDateTime h = target.withMinute(0).withSecond(0).withNano(0);
        List<WeatherHour> l = hourly(base, nx, ny, h, h.plusHours(1));
        return l.isEmpty() ? Optional.empty() : Optional.of(l.getFirst());
    }

    public Air air(String sido) {
        return jdbc.sql("""
                WITH last AS (SELECT max(data_time) AS t FROM env.air_quality WHERE sido_name = :s)
                SELECT last.t, percentile_disc(0.5) WITHIN GROUP (ORDER BY pm10) AS pm10,
                       percentile_disc(0.5) WITHIN GROUP (ORDER BY pm25) AS pm25,
                       percentile_disc(0.5) WITHIN GROUP (ORDER BY pm25_grade) AS g25,
                       percentile_disc(0.5) WITHIN GROUP (ORDER BY khai_grade) AS khai, count(*) AS n
                FROM env.air_quality a, last WHERE a.sido_name = :s AND a.data_time = last.t GROUP BY last.t""")
                .param("s", sido).query((rs, i) -> new Air(Times.kst(rs.getObject(1, OffsetDateTime.class)),
                        (Integer) rs.getObject(2), (Integer) rs.getObject(3), toInt(rs.getObject(4)), toInt(rs.getObject(5)),
                        rs.getInt(6))).optional().orElse(null);
    }

    public Incidents incidents(String cid, OffsetDateTime since, int limit) {
        String where = cid == null ? "sent_at >= :s" : "sent_at >= :s AND :c = ANY(corridor_ids)";
        var q = jdbc.sql("""
                SELECT sent_at, type_code, type_name, route_name, direction_txt, process_name, content, corridor_ids
                FROM ts.road_incident WHERE %s AND coalesce(type_code, '') <> '15'
                ORDER BY sent_at DESC LIMIT :l""".formatted(where)).param("s", since).param("l", limit);
        if (cid != null) q = q.param("c", cid);
        List<Incident> items = q.query((rs, i) -> new Incident(Times.kst(rs.getObject(1, OffsetDateTime.class)),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                Arrays.asList((String[]) rs.getArray(8).getArray()))).list();
        return new Incidents(cid, since, items,
                "도로공사 실시간 문자 안내. 코리도 매칭 규칙 M-v1: 노선명 일치 + (코리도 영업소명 언급 또는 코리도 주 노선). "
                        + "corridorIds 가 비어 있으면 '전체'. 이벤트/홍보(유형 15)는 제외.");
    }

    static Integer toInt(Object o) { return o == null ? null : ((Number) o).intValue(); }

    static String skyName(String code) {
        if (code == null) return null;
        return switch (code.trim()) {
            case "1" -> "맑음";
            case "3" -> "구름많음";
            case "4" -> "흐림";
            default -> code;
        };
    }
}
