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

/**
 * 철도 조회 — 모든 계산은 **역 쌍**(출발역 A → 도착역 B) 기준이며 SQL 함수 rail.od_trips (V6, 규칙 P-i1) 를 쓴다.
 * 길(미리 정한 도시 쌍)의 철도 조회도 그 길의 역 쌍으로 바꿔 같은 경로를 탄다.
 */
@Service
public class RailService {
    private final JdbcClient jdbc;
    private final AppProperties props;

    public RailService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public record Pair(String dep, String arr) {}

    /** 길 · 방향 → 역 쌍 */
    public Pair pairOf(String cid, String dir) {
        return jdbc.sql("SELECT dep_stn_cd, arr_stn_cd FROM ref.corridor_rail WHERE corridor_id = :c AND direction = :d")
                .param("c", cid).param("d", dir).query((rs, i) -> new Pair(rs.getString(1), rs.getString(2))).optional()
                .orElseThrow(() -> ApiException.corridorNotFound(cid));
    }

    public String stationName(String code) {
        return jdbc.sql("SELECT stn_nm FROM ref.station WHERE stn_cd = :s").param("s", code).query(String.class).optional()
                .orElseThrow(() -> ApiException.invalid("역 코드 '" + code + "' 를 찾을 수 없습니다."));
    }

    /** 가장 최근 운행일 (전국) */
    public Optional<LocalDate> latestDate() {
        return jdbc.sql("SELECT max(run_ymd) FROM rail.run_plan").query(LocalDate.class).optional();
    }

    /**
     * 시간표 기준일: 코레일 API 는 향후 운행계획을 주지 않으므로(U-6) 목표일과 같은 요일의 최근 운행일.
     * 없으면 가장 최근 운행일.
     */
    public Optional<Map.Entry<LocalDate, String>> referenceDate(LocalDate target) {
        LocalDate same = jdbc.sql("""
                SELECT max(run_ymd) FROM rail.run_plan
                WHERE run_ymd >= :t::date - 28 AND run_ymd < :t AND extract(isodow FROM run_ymd) = :dow""")
                .param("t", target).param("dow", target.getDayOfWeek().getValue()).query(LocalDate.class).optional().orElse(null);
        if (same != null) return Optional.of(Map.entry(same, "같은 요일 최근 운행일"));
        return latestDate().map(d -> Map.entry(d, "가장 최근 운행일 (같은 요일 없음)"));
    }

    /**
     * ready(역에 도착하는 시각) 이후 A→B 첫 열차들. 그날 남은 열차가 없으면 **다음 날 첫차**부터 (심야 출발).
     * 기준일 시간표를 목표일로 옮겨 표시한다.
     */
    public NextTrains nextTrains(String dep, String arr, OffsetDateTime ready, int limit) {
        OffsetDateTime r = Times.kst(ready);
        NextTrains today = nextTrainsOn(dep, arr, r.toLocalDate(), r.toLocalTime(), limit);
        if (!today.trains().isEmpty() || today.referenceDate() == null) return today;
        NextTrains tomorrow = nextTrainsOn(dep, arr, r.toLocalDate().plusDays(1), LocalTime.MIN, limit);
        return tomorrow.trains().isEmpty() ? today
                : new NextTrains(tomorrow.referenceDate(), tomorrow.basis() + " · 다음 날 첫차", tomorrow.trains());
    }

    NextTrains nextTrainsOn(String dep, String arr, LocalDate targetDate, LocalTime readyTime, int limit) {
        var ref = referenceDate(targetDate);
        if (ref.isEmpty()) return new NextTrains(null, "운행 데이터 없음", List.of());
        LocalDate refDate = ref.get().getKey();
        List<Object[]> rows = jdbc.sql("""
                SELECT trn_no, coalesce(est_plan_dep_at, act_dep_at) AS dep, coalesce(est_plan_arr_at, act_arr_at) AS arr
                FROM rail.od_trips(:a, :b, :r, :r)
                WHERE coalesce(est_plan_dep_at, act_dep_at)::time >= :ready::time
                  AND coalesce(est_plan_dep_at, act_dep_at)::date = :r
                ORDER BY dep LIMIT :lim""")
                .param("a", dep).param("b", arr).param("r", refDate).param("ready", readyTime).param("lim", limit)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class)})
                .list();
        Map<String, TrainStats> stats = stats30d(dep, arr, refDate, rows.stream().map(r -> (String) r[0]).toList());
        long shift = ChronoUnit.DAYS.between(refDate, targetDate);
        List<NextTrain> out = new ArrayList<>();
        for (Object[] r : rows) {
            OffsetDateTime d = Times.kst((OffsetDateTime) r[1]).plusDays(shift);
            OffsetDateTime a = Times.kst((OffsetDateTime) r[2]).plusDays(shift);
            TrainStats s = stats.get((String) r[0]);
            out.add(new NextTrain((String) r[0], d, a, d.format(Times.HM), a.format(Times.HM),
                    (int) Duration.between(d, a).toMinutes(), s == null ? null : s.avgArrDelayMin(),
                    s == null ? null : s.onTimeRate(), s == null ? 0 : s.samples(), s != null && s.delayEstimated()));
        }
        return new NextTrains(refDate.toString(), ref.get().getValue(), out);
    }

    public record First(String trnNo, OffsetDateTime dep, OffsetDateTime arr) {}

    /** ready 이후 A→B 첫 열차 (통계 없이 — 역 조합 비교용). 그날 없으면 다음 날 첫차. 시각은 목표일 기준으로 옮김. */
    public Optional<First> firstTrain(String dep, String arr, OffsetDateTime ready) {
        OffsetDateTime r = Times.kst(ready);
        Optional<First> f = firstOn(dep, arr, r.toLocalDate(), r.toLocalTime());
        return f.isPresent() ? f : firstOn(dep, arr, r.toLocalDate().plusDays(1), LocalTime.MIN);
    }

    private Optional<First> firstOn(String dep, String arr, LocalDate target, LocalTime ready) {
        var ref = referenceDate(target);
        if (ref.isEmpty()) return Optional.empty();
        LocalDate refDate = ref.get().getKey();
        long shift = ChronoUnit.DAYS.between(refDate, target);
        return jdbc.sql("""
                SELECT trn_no, coalesce(est_plan_dep_at, act_dep_at) AS dep, coalesce(est_plan_arr_at, act_arr_at) AS arr
                FROM rail.od_trips(:a, :b, :r, :r)
                WHERE coalesce(est_plan_dep_at, act_dep_at)::time >= :ready::time
                  AND coalesce(est_plan_dep_at, act_dep_at)::date = :r
                ORDER BY dep LIMIT 1""")
                .param("a", dep).param("b", arr).param("r", refDate).param("ready", ready)
                .query((rs, i) -> new First(rs.getString(1), Times.kst(rs.getObject(2, OffsetDateTime.class)).plusDays(shift),
                        Times.kst(rs.getObject(3, OffsetDateTime.class)).plusDays(shift))).optional();
    }

    public Map<String, TrainStats> stats30d(String dep, String arr, LocalDate end, List<String> trains) {
        Map<String, TrainStats> m = new HashMap<>();
        if (trains.isEmpty()) return m;
        jdbc.sql("""
                SELECT trn_no, count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       avg(ride_min) AS ride, bool_or(arr_basis = 'EST') AS est
                FROM rail.od_trips(:a, :b, :f, :t) WHERE trn_no IN (:trains)
                GROUP BY trn_no""")
                .param("thr", props.onTimeThresholdMin()).param("a", dep).param("b", arr)
                .param("f", end.minusDays(29)).param("t", end).param("trains", trains)
                .query(rs -> {
                    m.put(rs.getString("trn_no"), new TrainStats(rs.getInt("samples"), rs.getInt("verified"),
                            round(rs, "rate", 3), round(rs, "avg_delay", 1), round(rs, "p90", 1), round(rs, "ride", 1),
                            rs.getBoolean("est")));
                });
        return m;
    }

    public Trains trains(String dep, String arr, LocalDate date) {
        String depName = stationName(dep), arrName = stationName(arr);
        LocalDate latest = latestDate().orElse(null);
        List<String> dates = latest == null ? List.of() : jdbc.sql("""
                SELECT DISTINCT run_ymd::text FROM rail.od_trips(:a, :b, :f, :t) ORDER BY 1 DESC""")
                .param("a", dep).param("b", arr).param("f", latest.minusDays(120)).param("t", latest).query(String.class).list();
        LocalDate d = date != null ? date : (dates.isEmpty() ? null : LocalDate.parse(dates.getFirst()));
        if (d == null) return new Trains(dep, arr, depName, arrName, null, List.of(), dates, "두 역을 잇는 직통 운행 기록이 없습니다.");
        List<Object[]> rows = jdbc.sql("""
                SELECT trn_no, act_dep_at, act_arr_at, est_plan_dep_at, est_plan_arr_at, dep_delay_min::float,
                       arr_delay_min::float, dep_basis, arr_basis, ride_min::float
                FROM rail.od_trips(:a, :b, :r, :r) ORDER BY coalesce(est_plan_dep_at, act_dep_at)""")
                .param("a", dep).param("b", arr).param("r", d)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6), rs.getObject(7), rs.getString(8),
                        rs.getString(9), rs.getDouble(10)}).list();
        Map<String, TrainStats> stats = stats30d(dep, arr, d, rows.stream().map(r -> (String) r[0]).toList());
        int thr = props.onTimeThresholdMin();
        List<TrainRun> runs = new ArrayList<>();
        for (Object[] r : rows) {
            Double arrDelay = (Double) r[6];
            runs.add(new TrainRun((String) r[0], Times.kst((OffsetDateTime) r[1]), Times.kst((OffsetDateTime) r[2]),
                    Times.kst((OffsetDateTime) r[3]), Times.kst((OffsetDateTime) r[4]), (Double) r[5], arrDelay,
                    (String) r[7], (String) r[8], (Double) r[9], arrDelay == null ? null : arrDelay <= thr,
                    stats.get((String) r[0])));
        }
        return new Trains(dep, arr, depName, arrName, d.toString(), runs, dates,
                "계획 시각: 시발·종착역은 운행계획과 정확히 비교(EXACT), 중간역은 시발 출발 지연과 종착 도착 지연을 "
                        + "운행 경과시간 비율로 보간한 추정(EST, ⚠). 통계는 기준일까지 최근 30일. 환승 경로는 다루지 않습니다.");
    }

    public Punctuality punctuality(String dep, String arr, LocalDate from, LocalDate to, String groupBy, Integer thresholdMin) {
        if (to.isBefore(from)) throw ApiException.invalid("to 는 from 이후여야 합니다.");
        if (ChronoUnit.DAYS.between(from, to) > 366) throw ApiException.invalid("조회 기간은 최대 366일입니다.");
        if (dep.equals(arr)) throw ApiException.invalid("출발역과 도착역이 같습니다.");
        int thr = thresholdMin == null ? props.onTimeThresholdMin() : thresholdMin;
        if (thr < 0 || thr > 60) throw ApiException.invalid("thresholdMin 은 0~60 입니다.");
        String keyExpr = switch (groupBy) {
            case "train" -> "trn_no";
            case "dow" -> "extract(isodow FROM run_ymd)::int::text";
            case "hour" -> "lpad(extract(hour FROM coalesce(est_plan_dep_at, act_dep_at))::int::text, 2, '0')";
            default -> throw ApiException.invalid("groupBy 는 train · dow · hour 중 하나입니다.");
        };
        String depName = stationName(dep), arrName = stationName(arr);
        List<PunctualityItem> items = jdbc.sql("""
                SELECT %s AS k, count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay, percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       avg(ride_min) AS ride, avg(CASE WHEN arr_basis = 'EST' THEN 1.0 ELSE 0.0 END) AS est
                FROM rail.od_trips(:a, :b, :f, :t) GROUP BY 1 ORDER BY %s""".formatted(keyExpr,
                        "train".equals(groupBy) ? "samples DESC, 1" : "1"))
                .param("thr", thr).param("a", dep).param("b", arr).param("f", from).param("t", to)
                .query((rs, i) -> new PunctualityItem(rs.getString("k"), rs.getInt("samples"), rs.getInt("verified"),
                        round(rs, "rate", 3), round(rs, "avg_delay", 1), round(rs, "p90", 1), round(rs, "ride", 1),
                        round(rs, "est", 3))).list();
        List<Bucket> hist = new ArrayList<>();
        Summary summary = jdbc.sql("""
                SELECT count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay, percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       count(*) FILTER (WHERE arr_delay_min <= 0) AS b0,
                       count(*) FILTER (WHERE arr_delay_min > 0 AND arr_delay_min <= 5) AS b1,
                       count(*) FILTER (WHERE arr_delay_min > 5 AND arr_delay_min <= 10) AS b2,
                       count(*) FILTER (WHERE arr_delay_min > 10 AND arr_delay_min <= 20) AS b3,
                       count(*) FILTER (WHERE arr_delay_min > 20 AND arr_delay_min <= 30) AS b4,
                       count(*) FILTER (WHERE arr_delay_min > 30) AS b5
                FROM rail.od_trips(:a, :b, :f, :t)""")
                .param("thr", thr).param("a", dep).param("b", arr).param("f", from).param("t", to)
                .query((rs, i) -> {
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
        return new Punctuality(dep, arr, depName, arrName, from.toString(), to.toString(), groupBy, thr, summary, items,
                hist, nation,
                Map.of("P-v1", "시발 출발·종착 도착을 운행계획과 정확 비교", "P-i1", "중간역 지연은 운행 경과시간 비율로 보간한 추정 (⚠)"),
                "운행계획(시발·종착 계획 시각)과 운행정보(역별 실제 출발·도착)를 비교한 값. 운행계획이 없는 열차는 "
                        + "'운행 확인 불가'로 정시율 분모에서 제외합니다. 직통 열차만 다룹니다(환승 제외).");
    }

    /** 역 검색 — 이름 포함 검색, 최근 7일 운행 편수가 많은 역부터 */
    public List<Station> stations(String q, int limit) {
        LocalDate latest = latestDate().orElse(LocalDate.now(Times.KST));
        return jdbc.sql("""
                SELECT s.stn_cd, s.stn_nm, s.lat, s.lon, coalesce(c.n, 0) AS n
                FROM ref.station s
                LEFT JOIN (SELECT stn_cd, count(*) AS n FROM rail.run_info
                           WHERE run_ymd > :l::date - 7 AND run_ymd <= :l GROUP BY 1) c USING (stn_cd)
                WHERE (:q = '' OR s.stn_nm LIKE '%' || :q || '%') AND coalesce(c.n, 0) > 0
                ORDER BY (s.stn_nm = :q) DESC, (s.stn_nm LIKE :q || '%') DESC, n DESC, s.stn_nm LIMIT :lim""")
                .param("l", latest).param("q", q == null ? "" : q.trim()).param("lim", limit)
                .query((rs, i) -> new Station(rs.getString(1), rs.getString(2), (Double) rs.getObject(3),
                        (Double) rs.getObject(4), rs.getInt(5))).list();
    }

    /** 좌표에서 가까운 (최근 14일 운행이 있는) 역 */
    public List<StationNear> near(double lat, double lon, double radiusKm, int limit) {
        LocalDate latest = latestDate().orElse(LocalDate.now(Times.KST));
        return jdbc.sql("""
                SELECT stn_cd, stn_nm, lat, lon, km FROM (
                  SELECT s.stn_cd, s.stn_nm, s.lat, s.lon, ops.km(:la, :lo, s.lat, s.lon) AS km
                  FROM ref.station s
                  WHERE s.lat IS NOT NULL AND EXISTS (SELECT 1 FROM rail.run_info r
                        WHERE r.stn_cd = s.stn_cd AND r.run_ymd > :l::date - 14 AND r.run_ymd <= :l)) x
                WHERE km <= :r ORDER BY km LIMIT :lim""")
                .param("la", lat).param("lo", lon).param("l", latest).param("r", radiusKm).param("lim", limit)
                .query((rs, i) -> new StationNear(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                        Math.round(rs.getDouble(5) * 10) / 10.0)).list();
    }

    static Double round(ResultSet rs, String col, int digits) throws SQLException {
        Object v = rs.getObject(col);
        if (v == null) return null;
        double d = ((Number) v).doubleValue();
        double f = Math.pow(10, digits);
        return Math.round(d * f) / f;
    }
}
