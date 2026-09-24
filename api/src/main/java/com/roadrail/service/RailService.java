package com.roadrail.service;

import com.roadrail.common.ApiException;
import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.web.dto.RailDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RailService {
    private final JdbcClient jdbc;
    private final AppProperties props;

    public RailService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 가장 최근 운행일 (코리도 기준). 없으면 empty. */
    public Optional<LocalDate> latestDate(String cid) {
        return Optional.ofNullable(jdbc.sql("SELECT max(run_ymd) FROM rail.corridor_trip WHERE corridor_id = :c")
                .param("c", cid).query(LocalDate.class).optional().orElse(null));
    }

    /**
     * 출발 시각(+역 접근 시간) 이후 첫 열차들. 코레일 API 는 향후 운행계획을 주지 않으므로(U-6)
     * 목표일과 **같은 요일의 가장 최근 운행일** 시간표를 기준으로 삼는다.
     */
    public NextTrains nextTrains(String cid, String dir, OffsetDateTime depart, int accessMin, int limit) {
        OffsetDateTime ready = depart.plusMinutes(accessMin);
        LocalDate ref = jdbc.sql("""
                SELECT max(run_ymd) FROM rail.corridor_trip WHERE corridor_id = :c AND direction = :d
                  AND run_ymd >= :today::date - 28 AND extract(isodow FROM run_ymd) = :dow""")
                .param("c", cid).param("d", dir).param("today", ready.toLocalDate()).param("dow", ready.getDayOfWeek().getValue())
                .query(LocalDate.class).optional().orElse(null);
        String basis = "같은 요일 최근 운행일";
        if (ref == null) {
            ref = jdbc.sql("SELECT max(run_ymd) FROM rail.corridor_trip WHERE corridor_id = :c AND direction = :d")
                    .param("c", cid).param("d", dir).query(LocalDate.class).optional().orElse(null);
            basis = "가장 최근 운행일 (같은 요일 없음)";
        }
        if (ref == null) return new NextTrains(null, "운행 데이터 없음", List.of());
        final LocalDate refDate = ref;
        LocalTime readyTime = ready.atZoneSameInstant(Times.KST).toLocalTime();
        List<Object[]> rows = jdbc.sql("""
                SELECT trn_no, coalesce(est_plan_dep_at, act_dep_at) AS dep, coalesce(est_plan_arr_at, act_arr_at) AS arr
                FROM rail.corridor_trip WHERE corridor_id = :c AND direction = :d AND run_ymd = :r
                  AND coalesce(est_plan_dep_at, act_dep_at)::time >= :ready::time
                  AND coalesce(est_plan_dep_at, act_dep_at)::date = :r
                ORDER BY dep LIMIT :lim""")
                .param("c", cid).param("d", dir).param("r", refDate).param("ready", readyTime).param("lim", limit)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class)})
                .list();
        Map<String, TrainStats> stats = stats30d(cid, dir, refDate, rows.stream().map(r -> (String) r[0]).toList());
        LocalDate targetDate = ready.atZoneSameInstant(Times.KST).toLocalDate();
        List<NextTrain> out = new ArrayList<>();
        for (Object[] r : rows) {
            OffsetDateTime dep = shift(Times.kst((OffsetDateTime) r[1]), refDate, targetDate);
            OffsetDateTime arr = shift(Times.kst((OffsetDateTime) r[2]), refDate, targetDate);
            TrainStats s = stats.get((String) r[0]);
            out.add(new NextTrain((String) r[0], dep, arr, dep.format(Times.HM), arr.format(Times.HM),
                    (int) Duration.between(dep, arr).toMinutes(), s == null ? null : s.avgArrDelayMin(),
                    s == null ? null : s.onTimeRate(), s == null ? 0 : s.samples(), s != null && s.delayEstimated()));
        }
        return new NextTrains(refDate.toString(), basis, out);
    }

    private static OffsetDateTime shift(OffsetDateTime t, LocalDate from, LocalDate to) {
        return t.plusDays(ChronoUnit.DAYS.between(from, to));
    }

    public Map<String, TrainStats> stats30d(String cid, String dir, LocalDate end, List<String> trains) {
        Map<String, TrainStats> m = new HashMap<>();
        if (trains.isEmpty()) return m;
        jdbc.sql("""
                SELECT trn_no, count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       avg(ride_min) AS ride, bool_or(arr_basis = 'EST') AS est
                FROM rail.corridor_trip WHERE corridor_id = :c AND direction = :d
                  AND run_ymd BETWEEN :f AND :t AND trn_no IN (:trains)
                GROUP BY trn_no""")
                .param("thr", props.onTimeThresholdMin()).param("c", cid).param("d", dir)
                .param("f", end.minusDays(29)).param("t", end).param("trains", trains)
                .query(rs -> {
                    m.put(rs.getString("trn_no"), new TrainStats(rs.getInt("samples"), rs.getInt("verified"),
                            round(rs, "rate", 3), round(rs, "avg_delay", 1), round(rs, "p90", 1), round(rs, "ride", 1),
                            rs.getBoolean("est")));
                });
        return m;
    }

    public Trains trains(String cid, String dir, LocalDate date) {
        LocalDate d = date != null ? date : latestDate(cid).orElse(null);
        var names = jdbc.sql("""
                SELECT sd.stn_nm, sa.stn_nm FROM ref.corridor_rail cr
                JOIN ref.station sd ON sd.stn_cd = cr.dep_stn_cd JOIN ref.station sa ON sa.stn_cd = cr.arr_stn_cd
                WHERE cr.corridor_id = :c AND cr.direction = :d""").param("c", cid).param("d", dir)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).optional().orElse(new String[]{null, null});
        List<String> dates = jdbc.sql("""
                SELECT DISTINCT run_ymd::text FROM rail.corridor_trip WHERE corridor_id = :c ORDER BY 1 DESC LIMIT 120""")
                .param("c", cid).query(String.class).list();
        if (d == null) return new Trains(cid, dir, null, names[0], names[1], List.of(), dates, "운행 데이터 없음");
        List<TrainRun> runs = new ArrayList<>();
        List<Object[]> rows = jdbc.sql("""
                SELECT trn_no, act_dep_at, act_arr_at, est_plan_dep_at, est_plan_arr_at, dep_delay_min::float,
                       arr_delay_min::float, dep_basis, arr_basis, ride_min::float
                FROM rail.corridor_trip WHERE corridor_id = :c AND direction = :d AND run_ymd = :r
                ORDER BY coalesce(est_plan_dep_at, act_dep_at)""").param("c", cid).param("d", dir).param("r", d)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6), rs.getObject(7), rs.getString(8),
                        rs.getString(9), rs.getDouble(10)}).list();
        Map<String, TrainStats> stats = stats30d(cid, dir, d, rows.stream().map(r -> (String) r[0]).toList());
        int thr = props.onTimeThresholdMin();
        for (Object[] r : rows) {
            Double arrDelay = (Double) r[6];
            runs.add(new TrainRun((String) r[0], Times.kst((OffsetDateTime) r[1]), Times.kst((OffsetDateTime) r[2]),
                    Times.kst((OffsetDateTime) r[3]), Times.kst((OffsetDateTime) r[4]), (Double) r[5], arrDelay,
                    (String) r[7], (String) r[8], (Double) r[9], arrDelay == null ? null : arrDelay <= thr,
                    stats.get((String) r[0])));
        }
        return new Trains(cid, dir, d.toString(), names[0], names[1], runs, dates,
                "계획 시각: 시발·종착역은 운행계획과 정확히 비교(EXACT), 중간역은 시발 출발 지연과 종착 도착 지연을 "
                        + "운행 경과시간 비율로 보간한 추정(EST, ⚠). 통계는 기준일까지 최근 30일.");
    }

    public Punctuality punctuality(String cid, String dir, LocalDate from, LocalDate to, String groupBy, Integer thresholdMin) {
        if (to.isBefore(from)) throw ApiException.invalid("to 는 from 이후여야 합니다.");
        if (ChronoUnit.DAYS.between(from, to) > 366) throw ApiException.invalid("조회 기간은 최대 366일입니다.");
        int thr = thresholdMin == null ? props.onTimeThresholdMin() : thresholdMin;
        if (thr < 0 || thr > 60) throw ApiException.invalid("thresholdMin 은 0~60 입니다.");
        String keyExpr = switch (groupBy) {
            case "train" -> "trn_no";
            case "dow" -> "extract(isodow FROM run_ymd)::int::text";
            case "hour" -> "lpad(extract(hour FROM coalesce(est_plan_dep_at, act_dep_at))::int::text, 2, '0')";
            default -> throw ApiException.invalid("groupBy 는 train · dow · hour 중 하나입니다.");
        };
        String where = "corridor_id = :c AND run_ymd BETWEEN :f AND :t" + (dir == null ? "" : " AND direction = :d");
        var q = jdbc.sql("""
                SELECT %s AS k, count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay, percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       avg(ride_min) AS ride, avg(CASE WHEN arr_basis = 'EST' THEN 1.0 ELSE 0.0 END) AS est
                FROM rail.corridor_trip WHERE %s GROUP BY 1 ORDER BY %s""".formatted(keyExpr, where,
                "train".equals(groupBy) ? "samples DESC, 1" : "1"))
                .param("thr", thr).param("c", cid).param("f", from).param("t", to);
        if (dir != null) q = q.param("d", dir);
        List<PunctualityItem> items = q.query((rs, i) -> new PunctualityItem(rs.getString("k"), rs.getInt("samples"),
                rs.getInt("verified"), round(rs, "rate", 3), round(rs, "avg_delay", 1), round(rs, "p90", 1),
                round(rs, "ride", 1), round(rs, "est", 3))).list();

        var sq = jdbc.sql("""
                SELECT count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay, percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       count(*) FILTER (WHERE arr_delay_min <= 0) AS b0,
                       count(*) FILTER (WHERE arr_delay_min > 0 AND arr_delay_min <= 5) AS b1,
                       count(*) FILTER (WHERE arr_delay_min > 5 AND arr_delay_min <= 10) AS b2,
                       count(*) FILTER (WHERE arr_delay_min > 10 AND arr_delay_min <= 20) AS b3,
                       count(*) FILTER (WHERE arr_delay_min > 20 AND arr_delay_min <= 30) AS b4,
                       count(*) FILTER (WHERE arr_delay_min > 30) AS b5
                FROM rail.corridor_trip WHERE %s""".formatted(where))
                .param("thr", thr).param("c", cid).param("f", from).param("t", to);
        if (dir != null) sq = sq.param("d", dir);
        List<Bucket> hist = new ArrayList<>();
        Summary summary = sq.query((rs, i) -> {
            String[] labels = {"≤0분", "1–5분", "6–10분", "11–20분", "21–30분", ">30분"};
            for (int b = 0; b < 6; b++) hist.add(new Bucket(labels[b], rs.getInt("b" + b)));
            return new Summary(rs.getInt("samples"), rs.getInt("verified"), rs.getInt("samples") - rs.getInt("verified"),
                    round(rs, "rate", 3), round(rs, "avg_delay", 1), round(rs, "p90", 1));
        }).single();
        Summary nation = jdbc.sql("""
                SELECT count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay, percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90
                FROM rail.train_punctuality WHERE run_ymd BETWEEN :f AND :t""")
                .param("thr", thr).param("f", from).param("t", to)
                .query((rs, i) -> new Summary(rs.getInt("samples"), rs.getInt("verified"),
                        rs.getInt("samples") - rs.getInt("verified"), round(rs, "rate", 3), round(rs, "avg_delay", 1),
                        round(rs, "p90", 1))).single();
        return new Punctuality(cid, dir, from.toString(), to.toString(), groupBy, thr, summary, items, hist, nation,
                Map.of("P-v1", "시발 출발·종착 도착을 운행계획과 정확 비교", "P-i1", "중간역 지연은 운행 경과시간 비율로 보간한 추정 (⚠)"),
                "운행계획(시발·종착 계획 시각)과 운행정보(역별 실제 출발·도착)를 비교한 값. 운행정보가 없는 열차는 "
                        + "'운행 확인 불가'로 정시율 분모에서 제외합니다.");
    }

    static Double round(ResultSet rs, String col, int digits) throws SQLException {
        Object v = rs.getObject(col);
        if (v == null) return null;
        double d = ((Number) v).doubleValue();
        double f = Math.pow(10, digits);
        return Math.round(d * f) / f;
    }
}
