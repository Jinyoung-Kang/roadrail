package com.roadrail.rail.data;

import com.roadrail.domain.RailRouter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 철도망 — 하루 정차 시간표(rail.day_stops) · OSM 선로 경로(ref.rail_link) · 구간 계획 시각(rail.od_trips) · 역 좌표 */
@Repository
public class RailNetworkRepository {
    private final JdbcClient jdbc;

    public RailNetworkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 구간의 기준일 계획 시각과 그 근거(EXACT 운행계획 · TT TAGO 시간표 · 그 밖은 보간) */
    public record LegPlanRow(OffsetDateTime dep, OffsetDateTime arr, String grade, String depBasis, String arrBasis) {}

    /** 그날 전국 여객열차의 역별 정차 (시각은 epoch 초) */
    public List<RailRouter.Stop> dayStops(LocalDate day) {
        return jdbc.sql("SELECT trn_no, run_seq, stn_cd, arr_at, dep_at FROM rail.day_stops(:d)")
                .param("d", day).query((rs, i) -> new RailRouter.Stop(rs.getString(1), rs.getInt(2), rs.getString(3),
                        epoch(rs.getObject(4, OffsetDateTime.class)), epoch(rs.getObject(5, OffsetDateTime.class)))).list();
    }

    /** 역 쌍별 OSM 선로 경로 — 키 '출발역>도착역', 값 [[lat, lon], …] */
    public Map<String, List<double[]>> trackLinks() {
        Map<String, List<double[]>> m = new HashMap<>();
        jdbc.sql("SELECT dep_stn_cd, arr_stn_cd, path::text FROM ref.rail_link").query(rs -> {
            m.put(rs.getString(1) + ">" + rs.getString(2), parsePath(rs.getString(3)));
        });
        return m;
    }

    /** 열차 trn 의 from → to 구간 기준일 계획 시각 */
    public Optional<LegPlanRow> legPlan(String from, String to, String trn, LocalDate ref) {
        return jdbc.sql("""
                SELECT est_plan_dep_at, est_plan_arr_at, grade, dep_basis, arr_basis FROM rail.od_trips(:a, :b, :r, :r)
                WHERE trn_no = :t LIMIT 1""")
                .param("a", from).param("b", to).param("r", ref).param("t", trn)
                .query((rs, i) -> new LegPlanRow(rs.getObject(1, OffsetDateTime.class), rs.getObject(2, OffsetDateTime.class),
                        rs.getString(3), rs.getString(4), rs.getString(5))).optional();
    }

    /** 역 좌표 [lat, lon] — 좌표가 없는 역은 빈 값 */
    public Optional<double[]> stationCoords(String code) {
        return jdbc.sql("SELECT lat, lon FROM ref.station WHERE stn_cd = :c AND lat IS NOT NULL")
                .param("c", code).query((rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2)}).optional();
    }

    static List<double[]> parsePath(String json) {
        List<double[]> out = new ArrayList<>();
        var mt = java.util.regex.Pattern.compile("\\[\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*\\]").matcher(json);
        while (mt.find()) out.add(new double[]{Double.parseDouble(mt.group(1)), Double.parseDouble(mt.group(2))});
        return out;
    }

    private static Long epoch(OffsetDateTime t) { return t == null ? null : t.toEpochSecond(); }
}
