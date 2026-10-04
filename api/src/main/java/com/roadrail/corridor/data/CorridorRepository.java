package com.roadrail.corridor.data;

import com.roadrail.corridor.model.CorridorDtos.EnvPoint;
import com.roadrail.corridor.model.CorridorDtos.Point;
import com.roadrail.corridor.model.CorridorDtos.RailPair;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 길 기준 정보 조회 — 길 · 영업소 구간 · 역 쌍 · 날씨 지점 · 두 지점과 맞는 길.
 * 쿼리와 행 매핑만 맡고, 경로 조립 · 판단 카드 구성은 corridor.app 이 한다.
 */
@Repository
public class CorridorRepository {
    private final JdbcClient jdbc;

    public CorridorRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 운영 중인 길인가 */
    public boolean isActive(String id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ref.corridor WHERE corridor_id = :id AND active)")
                .param("id", id).query(Boolean.class).single();
    }

    public String name(String cid) {
        return jdbc.sql("SELECT name FROM ref.corridor WHERE corridor_id = :c").param("c", cid).query(String.class).single();
    }

    /** 운영 중인 길 (화면 순서) */
    public record CorridorRow(String id, String name, String originCity, String destCity) {}

    public List<CorridorRow> activeCorridors() {
        return jdbc.sql("""
                SELECT corridor_id, name, origin_city, dest_city FROM ref.corridor WHERE active ORDER BY sort_order""")
                .query((rs, i) -> new CorridorRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    /** 영업소 구간 하나 — 시작 · 끝 영업소와 구간 거리 */
    public record RoadSegment(String corridorId, String direction, double distanceKm, Point start, Point end) {}

    /** 모든 길의 영업소 구간 (길 · 방향 · 순서 순) */
    public List<RoadSegment> roadSegments() {
        return jdbc.sql("""
                SELECT r.corridor_id, r.direction, r.seq, r.distance_km,
                       us.unit_code AS s_code, us.unit_name AS s_name, us.lat AS s_lat, us.lon AS s_lon,
                       ue.unit_code AS e_code, ue.unit_name AS e_name, ue.lat AS e_lat, ue.lon AS e_lon
                FROM ref.corridor_road r
                JOIN ref.toll_unit us ON us.unit_code = r.start_unit_code
                JOIN ref.toll_unit ue ON ue.unit_code = r.end_unit_code
                ORDER BY r.corridor_id, r.direction, r.seq""")
                .query((rs, i) -> new RoadSegment(rs.getString("corridor_id"), rs.getString("direction"), rs.getDouble("distance_km"),
                        new Point(rs.getString("s_code"), rs.getString("s_name"), rs.getDouble("s_lat"), rs.getDouble("s_lon")),
                        new Point(rs.getString("e_code"), rs.getString("e_name"), rs.getDouble("e_lat"), rs.getDouble("e_lon"))))
                .list();
    }

    /** 길 → 방향 → 출발 · 도착역 */
    public Map<String, Map<String, RailPair>> railPairsByCorridor() {
        Map<String, Map<String, RailPair>> rails = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT cr.corridor_id, cr.direction, sd.stn_cd AS d_cd, sd.stn_nm AS d_nm, sd.lat AS d_lat, sd.lon AS d_lon,
                       sa.stn_cd AS a_cd, sa.stn_nm AS a_nm, sa.lat AS a_lat, sa.lon AS a_lon
                FROM ref.corridor_rail cr JOIN ref.station sd ON sd.stn_cd = cr.dep_stn_cd
                JOIN ref.station sa ON sa.stn_cd = cr.arr_stn_cd""").query(rs -> {
            rails.computeIfAbsent(rs.getString("corridor_id"), k -> new LinkedHashMap<>()).put(rs.getString("direction"),
                    new RailPair(new Point(rs.getString("d_cd"), rs.getString("d_nm"), (Double) rs.getObject("d_lat"), (Double) rs.getObject("d_lon")),
                            new Point(rs.getString("a_cd"), rs.getString("a_nm"), (Double) rs.getObject("a_lat"), (Double) rs.getObject("a_lon"))));
        });
        return rails;
    }

    /** 길 → 날씨 · 대기 지점 (출발 → 도착 순) */
    public Map<String, List<EnvPoint>> envPointsByCorridor() {
        Map<String, List<EnvPoint>> envs = new LinkedHashMap<>();
        jdbc.sql("SELECT corridor_id, role, name, lat, lon, nx, ny, sido_name FROM ref.corridor_env_point ORDER BY corridor_id, role DESC")
                .query(rs -> {
                    envs.computeIfAbsent(rs.getString("corridor_id"), k -> new ArrayList<>()).add(new EnvPoint(
                            rs.getString("role"), rs.getString("name"), rs.getDouble("lat"), rs.getDouble("lon"),
                            rs.getInt("nx"), rs.getInt("ny"), rs.getString("sido_name")));
                });
        return envs;
    }

    /** 길 · 방향의 도로 양 끝 — 첫 구간의 시작 영업소 · 마지막 구간의 끝 영업소와 전체 거리 */
    public record RoadEnds(String fromName, String toName, double fromLat, double fromLon, double toLat, double toLon,
                           double distanceKm) {}

    public RoadEnds roadEnds(String cid, String dir) {
        return jdbc.sql("""
                SELECT (array_agg(us.unit_name ORDER BY r.seq))[1], (array_agg(ue.unit_name ORDER BY r.seq DESC))[1],
                       (array_agg(us.lat ORDER BY r.seq))[1], (array_agg(us.lon ORDER BY r.seq))[1],
                       (array_agg(ue.lat ORDER BY r.seq DESC))[1], (array_agg(ue.lon ORDER BY r.seq DESC))[1],
                       sum(r.distance_km)::float
                FROM ref.corridor_road r JOIN ref.toll_unit us ON us.unit_code = r.start_unit_code
                JOIN ref.toll_unit ue ON ue.unit_code = r.end_unit_code
                WHERE r.corridor_id = :c AND r.direction = :d""").param("c", cid).param("d", dir)
                .query((rs, i) -> new RoadEnds(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getDouble(7))).single();
    }

    /** 길 · 방향의 출발 · 도착역 이름 */
    public record StationNames(String dep, String arr) {}

    public StationNames railStationNames(String cid, String dir) {
        return jdbc.sql("""
                SELECT sd.stn_nm, sa.stn_nm FROM ref.corridor_rail cr JOIN ref.station sd ON sd.stn_cd = cr.dep_stn_cd
                JOIN ref.station sa ON sa.stn_cd = cr.arr_stn_cd WHERE cr.corridor_id = :c AND cr.direction = :d""")
                .param("c", cid).param("d", dir).query((rs, i) -> new StationNames(rs.getString(1), rs.getString(2))).single();
    }

    /** 길의 날씨 · 대기 지점 — 역할(origin · dest) · 이름 · 단기예보 격자 · 시도 */
    public record EnvPointRow(String role, String name, int nx, int ny, String sido) {}

    public List<EnvPointRow> envPoints(String cid) {
        return jdbc.sql("""
                SELECT role, name, nx, ny, sido_name FROM ref.corridor_env_point WHERE corridor_id = :c""")
                .param("c", cid).query((rs, i) -> new EnvPointRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getInt(4), rs.getString(5))).list();
    }

    /**
     * 단기예보 전체의 가장 최근 발표 시각 — 판단 카드의 '날씨 신선도' 표시용. 환경 기능의 표(env.weather_fcst)를 읽기만 한다:
     * env.app.EnvService 에는 격자 구분 없는 최근 발표를 주는 공개 메서드가 없다.
     */
    public Optional<OffsetDateTime> latestWeatherBase() {
        return jdbc.sql("SELECT max(base_at) FROM env.weather_fcst").query(OffsetDateTime.class).optional();
    }

    /** 두 지점과 맞는 길 · 방향 */
    public record EndsMatch(String corridorId, String direction) {}

    /** 두 지점이 운영 중인 길의 끝(출발 · 도착 도시 역)과 각각 30km 안인 길 · 방향 중 가장 가까운 것 */
    public Optional<EndsMatch> matchByEnds(double fromLat, double fromLon, double toLat, double toLon) {
        return jdbc.sql("""
                WITH p AS (
                  SELECT o.corridor_id, o.lat AS olat, o.lon AS olon, d.lat AS dlat, d.lon AS dlon
                  FROM ref.corridor_env_point o JOIN ref.corridor_env_point d
                    ON d.corridor_id = o.corridor_id AND o.role = 'origin' AND d.role = 'dest'
                  JOIN ref.corridor c ON c.corridor_id = o.corridor_id AND c.active)
                SELECT corridor_id, dir FROM (
                  SELECT corridor_id, 'DN' AS dir, greatest(ops.km(olat, olon, :fla, :flo), ops.km(dlat, dlon, :tla, :tlo)) AS m FROM p
                  UNION ALL
                  SELECT corridor_id, 'UP', greatest(ops.km(dlat, dlon, :fla, :flo), ops.km(olat, olon, :tla, :tlo)) FROM p) x
                WHERE m <= 30 ORDER BY m LIMIT 1""")
                .param("fla", fromLat).param("flo", fromLon).param("tla", toLat).param("tlo", toLon)
                .query((rs, i) -> new EndsMatch(rs.getString(1), rs.getString(2))).optional();
    }
}
