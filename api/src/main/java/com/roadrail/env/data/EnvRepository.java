package com.roadrail.env.data;

import com.roadrail.env.model.EnvDtos.Air;
import com.roadrail.env.model.EnvDtos.Incident;
import com.roadrail.shared.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.*;

/** 환경 데이터 조회 — 길의 날씨 지점 · 단기예보 · 대기질 · 돌발 안내. 쿼리와 행 매핑만 맡고 판단은 env.app 이 한다 */
@Repository
public class EnvRepository {
    private final JdbcClient jdbc;

    public EnvRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 길의 날씨 · 대기 지점 (출발 → 도착 순) */
    public record EnvPoint(String role, String name, String sido, int nx, int ny) {}

    public List<EnvPoint> envPoints(String cid) {
        return jdbc.sql("""
                SELECT role, name, sido_name, nx, ny FROM ref.corridor_env_point WHERE corridor_id = :c ORDER BY role DESC""")
                .param("c", cid).query((rs, i) -> new EnvPoint(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5))).list();
    }

    /** 그 격자의 가장 최근 단기예보 발표 시각 */
    public Optional<OffsetDateTime> latestForecastBase(int nx, int ny) {
        return jdbc.sql("SELECT max(base_at) FROM env.weather_fcst WHERE nx = :x AND ny = :y")
                .param("x", nx).param("y", ny).query(OffsetDateTime.class).optional();
    }

    /** 시각별 예보 항목 — 시각 · 항목마다 그 시각을 가진 가장 최근 발표의 값 (시각 순) */
    public Map<OffsetDateTime, Map<String, String>> forecastByHour(int nx, int ny, OffsetDateTime from, OffsetDateTime to) {
        Map<OffsetDateTime, Map<String, String>> byHour = new TreeMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (fcst_at, category) fcst_at, category, value FROM env.weather_fcst
                WHERE nx = :x AND ny = :y AND fcst_at >= :f AND fcst_at < :t
                ORDER BY fcst_at, category, base_at DESC""")
                .param("x", nx).param("y", ny).param("f", from).param("t", to)
                .query(rs -> {
                    byHour.computeIfAbsent(Times.kst(rs.getObject(1, OffsetDateTime.class)), k -> new HashMap<>())
                            .put(rs.getString(2), rs.getString(3));
                });
        return byHour;
    }

    /** 시도 내 측정소의 가장 최근 측정값 중앙값 — 없으면 null */
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

    /** 도로공사 문자 안내 — since 이후 · (길이 있으면) 그 길에 매칭된 것, 최근 순 */
    public List<Incident> exIncidents(String cid, OffsetDateTime since, int limit) {
        String where = cid == null ? "sent_at >= :s" : "sent_at >= :s AND :c = ANY(corridor_ids)";
        var q = jdbc.sql("SELECT " + INCIDENT_COLS + """
                 FROM ts.road_incident WHERE %s AND source = 'EX' AND coalesce(type_code, '') <> '15'
                ORDER BY sent_at DESC LIMIT :l""".formatted(where)).param("s", since).param("l", limit);
        if (cid != null) q = q.param("c", cid);
        return q.query((rs, i) -> incident(rs)).list();
    }

    /** 지금 안내 중인 돌발 중 좌표가 상자 안인 것 */
    public List<Incident> activeIncidentsIn(double s, double n, double w, double e) {
        return jdbc.sql("SELECT " + INCIDENT_COLS + " FROM ts.road_incident WHERE " + ACTIVE
                        + " AND lat BETWEEN :s AND :n AND lon BETWEEN :w AND :e")
                .param("s", s).param("n", n).param("w", w).param("e", e)
                .query((rs, i) -> incident(rs)).list();
    }

    /** 지금 안내 중인 돌발 중 좌표 없이 그 길에 매칭된 것 — 최근 10건 */
    public List<Incident> activeUnlocatedIncidents(String corridorId) {
        return jdbc.sql("SELECT " + INCIDENT_COLS + " FROM ts.road_incident WHERE " + ACTIVE
                        + " AND lat IS NULL AND :c = ANY(corridor_ids) ORDER BY sent_at DESC LIMIT 10")
                .param("c", corridorId).query((rs, i) -> incident(rs)).list();
    }

    static final String INCIDENT_COLS = "sent_at, type_code, type_name, route_name, direction_txt, process_name, content, corridor_ids, "
            + "lat, lon, point_name, last_seen_at, source, end_at, lane";

    /** 문자 안내 · UTIC 목록을 5분마다 받으므로, 20분 안에 목록에서 본 안내를 '지금 안내 중'으로 본다 */
    static final String ACTIVE = "last_seen_at > now() - interval '20 minutes' AND coalesce(type_code, '') <> '15'";

    static Incident incident(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Incident(Times.kst(rs.getObject("sent_at", OffsetDateTime.class)), rs.getString("type_code"),
                rs.getString("type_name"), rs.getString("route_name"), rs.getString("direction_txt"), rs.getString("process_name"),
                rs.getString("content"), Arrays.asList((String[]) rs.getArray("corridor_ids").getArray()),
                (Double) rs.getObject("lat"), (Double) rs.getObject("lon"), rs.getString("point_name"),
                Times.kst(rs.getObject("last_seen_at", OffsetDateTime.class)), null, rs.getString("source"),
                rs.getObject("end_at") == null ? null : Times.kst(rs.getObject("end_at", OffsetDateTime.class)), rs.getString("lane"));
    }

    static Integer toInt(Object o) { return o == null ? null : ((Number) o).intValue(); }
}
