package com.roadrail.rail.data;

import com.roadrail.domain.CompensationRule;

import com.roadrail.rail.model.RailDtos.DelayBands;
import com.roadrail.rail.model.RailDtos.Station;
import com.roadrail.rail.model.RailDtos.StationNear;
import com.roadrail.rail.model.RailDtos.Summary;
import com.roadrail.rail.model.RailDtos.TrainStats;
import com.roadrail.shared.Rows;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 철도 조회 — 운행계획(rail.run_plan) · 운행정보(rail.run_info) · 역(ref.station) · 길의 역 쌍(ref.corridor_rail) ·
 * 역 쌍 운행(SQL 함수 rail.od_trips · rail.od_trips_real) 과 그 통계. 조회와 행 읽기만 하고, 기준일 고르기 · 시각 옮기기 ·
 * 캐시는 서비스(rail.app)가 맡는다.
 */
@Repository
public class RailRepository {
    private final JdbcClient jdbc;

    public RailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 길 · 방향의 출발역 · 도착역 */
    public record StationPair(String dep, String arr) {}

    /** 역 쌍 운행 한 편 — 시각은 DB 값 그대로(시간대 변환 전) */
    public record OdTrip(String trnNo, OffsetDateTime dep, OffsetDateTime arr) {}

    /** 운행계획의 시발 · 종착역 이름 */
    public record TrainEnds(String trnNo, String originName, String terminusName) {}

    /** 날짜별 역 쌍 운행 수 · 계획 시각을 확인한 수 */
    public record DayCount(LocalDate day, int runs, int verified) {}

    /** 하루 역 쌍 운행 한 편 (rail.od_trips_real) — 시각은 DB 값 그대로(시간대 변환 전) */
    public record RunRow(String trnNo, OffsetDateTime actDepAt, OffsetDateTime actArrAt, OffsetDateTime planDepAt,
                         OffsetDateTime planArrAt, Double depDelayMin, Double arrDelayMin, String depBasis, String arrBasis,
                         double rideMin, String grade) {}

    /**
     * 정시성 묶음 행 — total 이면 전체 요약 행(GROUPING SETS 의 ()), 아니면 묶음 key 의 행.
     * buckets = 도착 지연 ≤0 · 1–5 · 6–10 · 11–20 · 21–30 · >30분 편수 (b0~b5)
     */
    public record PunctualityRow(String key, boolean total, int samples, int verified, Double onTimeRate, Double avgArrDelayMin,
                                 Double p90ArrDelayMin, Double avgRideMin, String grade, List<Integer> buckets, DelayBands delayBands) {}

    /** 배상 기준 구간 집계 열(ge20 …)과 분모(verified)로 DelayBands */
    static DelayBands bands(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DelayBands(rs.getInt("verified"), rs.getInt("ge20"), rs.getInt("ge40"), rs.getInt("ge60"), rs.getInt("ge90"),
                rs.getInt("ge120"));
    }

    /** 정시성 묶음 키 — groupBy 값마다의 SQL 식 */
    public enum GroupKey {
        TRAIN("trn_no"),
        // 공휴일(한국천문연구원 특일 정보)은 요일과 따로 'H' — 임시열차가 섞여 평소 요일과 다르다
        DOW("CASE WHEN EXISTS (SELECT 1 FROM ref.holiday h WHERE h.day = o.run_ymd) THEN 'H' "
                + "ELSE extract(isodow FROM o.run_ymd)::int::text END"),
        HOUR("lpad(extract(hour FROM coalesce(o.plan_dep_at, o.act_dep_at))::int::text, 2, '0')");

        private final String expr;

        GroupKey(String expr) {
            this.expr = expr;
        }
    }

    public Optional<StationPair> corridorStations(String corridorId, String direction) {
        return jdbc.sql("SELECT dep_stn_cd, arr_stn_cd FROM ref.corridor_rail WHERE corridor_id = :c AND direction = :d")
                .param("c", corridorId).param("d", direction).query((rs, i) -> new StationPair(rs.getString(1), rs.getString(2))).optional();
    }

    public Optional<String> stationName(String code) {
        return jdbc.sql("SELECT stn_nm FROM ref.station WHERE stn_cd = :s").param("s", code).query(String.class).optional();
    }

    /** 가장 최근 운행일 (전국) */
    public Optional<LocalDate> latestRunDate() {
        return jdbc.sql("SELECT max(run_ymd) FROM rail.run_plan").query(LocalDate.class).optional();
    }

    /** 목표일 앞 8주 안의 같은 요일 운행일 — 최근 것부터 */
    public List<LocalDate> sameWeekdayRunDates(LocalDate target) {
        return jdbc.sql("""
                SELECT DISTINCT run_ymd FROM rail.run_plan
                WHERE run_ymd >= :t::date - 56 AND run_ymd < :t AND extract(isodow FROM run_ymd) = :dow
                ORDER BY run_ymd DESC""")
                .param("t", target).param("dow", target.getDayOfWeek().getValue()).query(LocalDate.class).list();
    }

    /** 기준일 ready 이후 출발하는 A→B 열차들 — 출발 순으로 limit 편 */
    public List<OdTrip> departuresAfter(String dep, String arr, LocalDate refDate, LocalTime ready, int limit) {
        return jdbc.sql("""
                SELECT trn_no, coalesce(est_plan_dep_at, act_dep_at) AS dep, coalesce(est_plan_arr_at, act_arr_at) AS arr
                FROM rail.od_trips(:a, :b, :r, :r)
                WHERE coalesce(est_plan_dep_at, act_dep_at)::time >= :ready::time
                  AND coalesce(est_plan_dep_at, act_dep_at)::date = :r
                ORDER BY dep LIMIT :lim""")
                .param("a", dep).param("b", arr).param("r", refDate).param("ready", ready).param("lim", limit)
                .query((rs, i) -> new OdTrip(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class)))
                .list();
    }

    /** 기준일 ready 이후 출발하는 A→B 첫 열차 */
    public Optional<OdTrip> firstDepartureAfter(String dep, String arr, LocalDate refDate, LocalTime ready) {
        return jdbc.sql("""
                SELECT trn_no, coalesce(est_plan_dep_at, act_dep_at) AS dep, coalesce(est_plan_arr_at, act_arr_at) AS arr
                FROM rail.od_trips(:a, :b, :r, :r)
                WHERE coalesce(est_plan_dep_at, act_dep_at)::time >= :ready::time
                  AND coalesce(est_plan_dep_at, act_dep_at)::date = :r
                ORDER BY dep LIMIT 1""")
                .param("a", dep).param("b", arr).param("r", refDate).param("ready", ready)
                .query((rs, i) -> new OdTrip(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class)))
                .optional();
    }

    /** 열차별 시발 · 종착역 이름 — 기간 안의 가장 최근 운행일 기준. trains 는 비어 있지 않아야 한다(IN 목록) */
    public List<TrainEnds> trainEnds(Collection<String> trains, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT DISTINCT ON (p.trn_no) p.trn_no, sd.stn_nm AS dep_nm, sa.stn_nm AS arr_nm
                FROM rail.run_plan p JOIN ref.station sd ON sd.stn_cd = p.dep_stn_cd JOIN ref.station sa ON sa.stn_cd = p.arr_stn_cd
                WHERE p.run_ymd BETWEEN :f AND :t AND p.trn_no IN (:trains)
                ORDER BY p.trn_no, p.run_ymd DESC""")
                .param("f", from).param("t", to).param("trains", new ArrayList<>(trains))
                .query((rs, i) -> new TrainEnds(rs.getString("trn_no"), rs.getString("dep_nm"), rs.getString("arr_nm")))
                .list();
    }

    /** 열차별 정시성 통계 (기간 [from, to], 정시 기준 thr 분) — 열차 번호 → 통계. trains 는 비어 있지 않아야 한다(IN 목록) */
    public Map<String, TrainStats> trainStats(String dep, String arr, LocalDate from, LocalDate to, List<String> trains, int thr) {
        Map<String, TrainStats> m = new HashMap<>();
        jdbc.sql("""
                SELECT trn_no, count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       avg(ride_min) AS ride
                FROM rail.od_trips_real(:a, :b, :f, :t) WHERE trn_no IN (:trains)
                GROUP BY trn_no""")
                .param("thr", thr).param("a", dep).param("b", arr)
                .param("f", from).param("t", to).param("trains", trains)
                .query(rs -> {
                    m.put(rs.getString("trn_no"), new TrainStats(rs.getInt("samples"), rs.getInt("verified"),
                            Rows.round(rs, "rate", 3), Rows.round(rs, "avg_delay", 1), Rows.round(rs, "p90", 1), Rows.round(rs, "ride", 1)));
                });
        return m;
    }

    /** 날짜별 역 쌍 운행 수 · 계획 시각을 확인한 수 — 최근 날짜부터 */
    public List<DayCount> dailyCounts(String dep, String arr, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT run_ymd, count(*) AS n, count(arr_delay_min) AS v FROM rail.od_trips_real(:a, :b, :f, :t)
                GROUP BY run_ymd ORDER BY run_ymd DESC""")
                .param("a", dep).param("b", arr).param("f", from).param("t", to)
                .query((rs, i) -> new DayCount(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3))).list();
    }

    /** 하루 역 쌍 운행 — 계획(없으면 실제) 출발 순 */
    public List<RunRow> runs(String dep, String arr, LocalDate day) {
        return jdbc.sql("""
                SELECT trn_no, act_dep_at, act_arr_at, plan_dep_at, plan_arr_at, dep_delay_min::float,
                       arr_delay_min::float, dep_basis, arr_basis, ride_min::float, grade
                FROM rail.od_trips_real(:a, :b, :r, :r) ORDER BY coalesce(plan_dep_at, act_dep_at)""")
                .param("a", dep).param("b", arr).param("r", day)
                .query((rs, i) -> new RunRow(rs.getString(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class),
                        rs.getObject(5, OffsetDateTime.class), (Double) rs.getObject(6), (Double) rs.getObject(7), rs.getString(8),
                        rs.getString(9), rs.getDouble(10), rs.getString(11))).list();
    }

    /** 역 쌍 정시성 — 한 번의 계산으로 묶음별 행 + 전체 요약 행(GROUPING SETS 의 ()), 역 쌍 운행 계산(od_trips)을 한 번만 */
    public List<PunctualityRow> punctuality(String dep, String arr, LocalDate from, LocalDate to, int thr, GroupKey key) {
        return jdbc.sql("""
                SELECT k, grouping(k) AS total, count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay, percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       avg(ride_min) AS ride,
                       (array_agg(grade ORDER BY run_ymd DESC) FILTER (WHERE grade IS NOT NULL))[1] AS grade,
                       count(*) FILTER (WHERE arr_delay_min <= 0) AS b0,
                       count(*) FILTER (WHERE arr_delay_min > 0 AND arr_delay_min <= 5) AS b1,
                       count(*) FILTER (WHERE arr_delay_min > 5 AND arr_delay_min <= 10) AS b2,
                       count(*) FILTER (WHERE arr_delay_min > 10 AND arr_delay_min <= 20) AS b3,
                       count(*) FILTER (WHERE arr_delay_min > 20 AND arr_delay_min <= 30) AS b4,
                       count(*) FILTER (WHERE arr_delay_min > 30) AS b5,
                       {bands}
                FROM (SELECT o.*, {key} AS k FROM rail.od_trips_real(:a, :b, :f, :t) o) x
                GROUP BY GROUPING SETS ((k), ())""".replace("{key}", key.expr).replace("{bands}", CompensationRule.sqlCounts("arr_delay_min")))
                .param("thr", thr).param("a", dep).param("b", arr).param("f", from).param("t", to)
                .query((rs, i) -> new PunctualityRow(rs.getString("k"), rs.getInt("total") == 1, rs.getInt("samples"),
                        rs.getInt("verified"), Rows.round(rs, "rate", 3), Rows.round(rs, "avg_delay", 1), Rows.round(rs, "p90", 1),
                        Rows.round(rs, "ride", 1), rs.getString("grade"),
                        List.of(rs.getInt("b0"), rs.getInt("b1"), rs.getInt("b2"), rs.getInt("b3"), rs.getInt("b4"), rs.getInt("b5")),
                        bands(rs)))
                .list();
    }

    /** 운행 중인 역(latest 까지 최근 7일 정차가 있는 역)과 정차 편수 */
    public List<Station> activeStations(LocalDate latest) {
        return jdbc.sql("""
                SELECT s.stn_cd, s.stn_nm, s.lat, s.lon, c.n
                FROM ref.station s
                JOIN (SELECT stn_cd, count(*) AS n FROM rail.run_info
                      WHERE run_ymd > :l::date - 7 AND run_ymd <= :l GROUP BY 1) c USING (stn_cd)""")
                .param("l", latest)
                .query((rs, i) -> new Station(rs.getString(1), rs.getString(2), (Double) rs.getObject(3),
                        (Double) rs.getObject(4), rs.getInt(5))).list();
    }

    /** 기간 전국 여객열차 종착역 기준 정시성(P-v1, rail.train_punctuality) */
    public Summary nationwide(LocalDate from, LocalDate to, int thr) {
        return jdbc.sql("""
                SELECT count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay, percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90,
                       {bands}
                FROM rail.train_punctuality WHERE run_ymd BETWEEN :f AND :t""".replace("{bands}", CompensationRule.sqlCounts("arr_delay_min")))
                .param("thr", thr).param("f", from).param("t", to)
                .query((rs, i) -> new Summary(rs.getInt("samples"), rs.getInt("verified"),
                        rs.getInt("samples") - rs.getInt("verified"), Rows.round(rs, "rate", 3), Rows.round(rs, "avg_delay", 1),
                        Rows.round(rs, "p90", 1), bands(rs))).single();
    }

    /** 좌표에서 radiusKm 안의 (latest 까지 최근 14일 운행이 있는) 역 — 가까운 순으로 limit 개 */
    public List<StationNear> stationsNear(double lat, double lon, double radiusKm, int limit, LocalDate latest) {
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
}
