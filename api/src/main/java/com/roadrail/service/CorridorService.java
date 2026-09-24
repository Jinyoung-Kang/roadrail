package com.roadrail.service;

import com.roadrail.common.ApiException;
import com.roadrail.web.dto.CorridorDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CorridorService {
    private final JdbcClient jdbc;

    public CorridorService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void require(String id) {
        Boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ref.corridor WHERE corridor_id = :id AND active)")
                .param("id", id).query(Boolean.class).single();
        if (!ok) throw ApiException.corridorNotFound(id);
    }

    public static String dir(String d) {
        if (d == null || !(d.equals("DN") || d.equals("UP"))) throw ApiException.invalid("dir 는 DN(하행) 또는 UP(상행) 입니다.");
        return d;
    }

    public List<Corridor> list() {
        var base = jdbc.sql("""
                SELECT corridor_id, name, origin_city, dest_city FROM ref.corridor WHERE active ORDER BY sort_order""")
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}).list();
        Map<String, Map<String, List<Point>>> paths = new LinkedHashMap<>();
        Map<String, Map<String, Double>> dist = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT r.corridor_id, r.direction, r.seq, r.distance_km,
                       us.unit_code AS s_code, us.unit_name AS s_name, us.lat AS s_lat, us.lon AS s_lon,
                       ue.unit_code AS e_code, ue.unit_name AS e_name, ue.lat AS e_lat, ue.lon AS e_lon
                FROM ref.corridor_road r
                JOIN ref.toll_unit us ON us.unit_code = r.start_unit_code
                JOIN ref.toll_unit ue ON ue.unit_code = r.end_unit_code
                ORDER BY r.corridor_id, r.direction, r.seq""").query(rs -> {
            String cid = rs.getString("corridor_id"), d = rs.getString("direction");
            List<Point> pts = paths.computeIfAbsent(cid, k -> new LinkedHashMap<>()).computeIfAbsent(d, k -> new ArrayList<>());
            if (pts.isEmpty()) pts.add(new Point(rs.getString("s_code"), rs.getString("s_name"), rs.getDouble("s_lat"), rs.getDouble("s_lon")));
            pts.add(new Point(rs.getString("e_code"), rs.getString("e_name"), rs.getDouble("e_lat"), rs.getDouble("e_lon")));
            dist.computeIfAbsent(cid, k -> new LinkedHashMap<>()).merge(d, rs.getDouble("distance_km"), Double::sum);
        });
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
        Map<String, List<EnvPoint>> envs = new LinkedHashMap<>();
        jdbc.sql("SELECT corridor_id, role, name, lat, lon, nx, ny, sido_name FROM ref.corridor_env_point ORDER BY corridor_id, role DESC")
                .query(rs -> {
                    envs.computeIfAbsent(rs.getString("corridor_id"), k -> new ArrayList<>()).add(new EnvPoint(
                            rs.getString("role"), rs.getString("name"), rs.getDouble("lat"), rs.getDouble("lon"),
                            rs.getInt("nx"), rs.getInt("ny"), rs.getString("sido_name")));
                });
        List<Corridor> out = new ArrayList<>();
        for (String[] b : base) {
            String id = b[0];
            Map<String, RoadPath> road = new LinkedHashMap<>();
            for (var e : paths.getOrDefault(id, Map.of()).entrySet()) {
                double km = dist.get(id).get(e.getKey());
                road.put(e.getKey(), new RoadPath(e.getValue().size() - 1, Math.round(km * 10) / 10.0, e.getValue()));
            }
            out.add(new Corridor(id, b[1], b[2], b[3], road, rails.getOrDefault(id, Map.of()), envs.getOrDefault(id, List.of())));
        }
        return out;
    }
}
