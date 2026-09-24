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
    private final TimetableService timetable;
    private final JsonCache cache;
    private final HolidayService holidays;

    public RailService(JdbcClient jdbc, AppProperties props, TimetableService timetable, JsonCache cache,
                       HolidayService holidays) {
        this.holidays = holidays;
        this.jdbc = jdbc;
        this.props = props;
        this.timetable = timetable;
        this.cache = cache;
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
    /**
     * 앞으로의 시간표는 공개 데이터에 없어 **같은 요일의 최근 운행일** 실제 시간표를 쓴다.
     * 공휴일에는 임시열차가 섞인다(실측: 추석 09-24(목) 931편 ↔ 09-17(목) 875편) → 평일 목표일에는 공휴일을 기준일로 고르지 않는다.
     * 목표일이 공휴일이면 같은 요일 최근 운행일을 쓰고, 공휴일 임시열차는 반영되지 않는다고 밝힌다.
     */
    public Optional<Map.Entry<LocalDate, String>> referenceDate(LocalDate target) {
        boolean targetHoliday = holidays.is(target);
        List<LocalDate> same = jdbc.sql("""
                SELECT DISTINCT run_ymd FROM rail.run_plan
                WHERE run_ymd >= :t::date - 56 AND run_ymd < :t AND extract(isodow FROM run_ymd) = :dow
                ORDER BY run_ymd DESC""")
                .param("t", target).param("dow", target.getDayOfWeek().getValue()).query(LocalDate.class).list();
        for (LocalDate d : same) {
            if (targetHoliday) {
                return Optional.of(Map.entry(d, "같은 요일 최근 운행일 · 목표일은 " + holidays.name(target).orElse("공휴일")
                        + " — 공휴일 임시열차는 반영되지 않음"));
            }
            if (!holidays.is(d)) return Optional.of(Map.entry(d, "같은 요일 최근 운행일 (공휴일 제외)"));
        }
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
        timetable.ensure(dep, arr, List.of(refDate), Duration.ofMillis(1500));
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
                    s == null ? null : s.onTimeRate(), s == null ? 0 : s.samples()));
        }
        return new NextTrains(refDate.toString(), ref.get().getValue(), out);
    }

    public record First(String trnNo, OffsetDateTime dep, OffsetDateTime arr) {}

    /** 열차 정보 — 운행계획(rail.run_plan)의 시발·종착역으로 'OO발 OO행'. 기간 안의 가장 최근 운행일 기준. */
    public Map<String, TrainMeta> trainMeta(Collection<String> trains, LocalDate from, LocalDate to) {
        Map<String, TrainMeta> m = new HashMap<>();
        if (trains.isEmpty()) return m;
        jdbc.sql("""
                SELECT DISTINCT ON (p.trn_no) p.trn_no, sd.stn_nm AS dep_nm, sa.stn_nm AS arr_nm
                FROM rail.run_plan p JOIN ref.station sd ON sd.stn_cd = p.dep_stn_cd JOIN ref.station sa ON sa.stn_cd = p.arr_stn_cd
                WHERE p.run_ymd BETWEEN :f AND :t AND p.trn_no IN (:trains)
                ORDER BY p.trn_no, p.run_ymd DESC""")
                .param("f", from).param("t", to).param("trains", new ArrayList<>(trains))
                .query(rs -> {
                    String o = rs.getString("dep_nm"), d = rs.getString("arr_nm");
                    m.put(rs.getString("trn_no"), new TrainMeta(o, d, o + "발 " + d + "행"));
                });
        return m;
    }

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
        timetable.ensure(dep, arr, List.of(refDate), Duration.ofMillis(1500));
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
                       avg(ride_min) AS ride
                FROM rail.od_trips_real(:a, :b, :f, :t) WHERE trn_no IN (:trains)
                GROUP BY trn_no""")
                .param("thr", props.onTimeThresholdMin()).param("a", dep).param("b", arr)
                .param("f", end.minusDays(29)).param("t", end).param("trains", trains)
                .query(rs -> {
                    m.put(rs.getString("trn_no"), new TrainStats(rs.getInt("samples"), rs.getInt("verified"),
                            round(rs, "rate", 3), round(rs, "avg_delay", 1), round(rs, "p90", 1), round(rs, "ride", 1)));
                });
        return m;
    }

    public Trains trains(String dep, String arr, LocalDate date) {
        String cacheKey = "rail:trains:v2:%s:%s:%s".formatted(dep, arr, date == null ? "latest" : date);
        Trains hit = cache.peek(cacheKey, Trains.class);
        if (hit != null) return hit;
        String depName = stationName(dep), arrName = stationName(arr);
        LocalDate latest = latestDate().orElse(null);
        // 최근 120일 날짜별 운행 수 · 계획 시각을 확인한 수를 한 번에 — 날짜 목록과 기본 날짜를 같이 고른다
        List<Object[]> perDay = latest == null ? List.of() : jdbc.sql("""
                SELECT run_ymd, count(*) AS n, count(arr_delay_min) AS v FROM rail.od_trips_real(:a, :b, :f, :t)
                GROUP BY run_ymd ORDER BY run_ymd DESC""")
                .param("a", dep).param("b", arr).param("f", latest.minusDays(120)).param("t", latest)
                .query((rs, i) -> new Object[]{rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3)}).list();
        List<String> dates = perDay.stream().map(r -> r[0].toString()).toList();
        // 날짜를 고르지 않았으면: 계획 시각을 확인할 수 있는(운행의 절반 이상) 가장 최근 날짜 — TAGO 시간표가 빠진 날은 건너뜀
        LocalDate d = date != null ? date : perDay.stream().filter(r -> (int) r[2] >= 0.5 * (int) r[1])
                .map(r -> (LocalDate) r[0]).findFirst().orElse(dates.isEmpty() ? null : LocalDate.parse(dates.getFirst()));
        if (d == null) return new Trains(dep, arr, depName, arrName, null, List.of(), dates, "두 역을 잇는 직통 운행 기록이 없습니다.");
        boolean ready = timetable.ensure(dep, arr, timetable.runDates(dep, arr, d.minusDays(29), d), Duration.ofMillis(2500));
        List<Object[]> rows = jdbc.sql("""
                SELECT trn_no, act_dep_at, act_arr_at, plan_dep_at, plan_arr_at, dep_delay_min::float,
                       arr_delay_min::float, dep_basis, arr_basis, ride_min::float, grade
                FROM rail.od_trips_real(:a, :b, :r, :r) ORDER BY coalesce(plan_dep_at, act_dep_at)""")
                .param("a", dep).param("b", arr).param("r", d)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6), rs.getObject(7), rs.getString(8),
                        rs.getString(9), rs.getDouble(10), rs.getString(11)}).list();
        Map<String, TrainStats> stats = stats30d(dep, arr, d, rows.stream().map(r -> (String) r[0]).toList());
        Map<String, TrainMeta> meta = trainMeta(rows.stream().map(r -> (String) r[0]).toList(), d, d);
        int thr = props.onTimeThresholdMin();
        List<TrainRun> runs = new ArrayList<>();
        for (Object[] r : rows) {
            Double arrDelay = (Double) r[6];
            runs.add(new TrainRun((String) r[0], Times.kst((OffsetDateTime) r[1]), Times.kst((OffsetDateTime) r[2]),
                    Times.kst((OffsetDateTime) r[3]), Times.kst((OffsetDateTime) r[4]), (Double) r[5], arrDelay,
                    (String) r[7], (String) r[8], (Double) r[9], arrDelay == null ? null : arrDelay <= thr,
                    stats.get((String) r[0]), meta.get((String) r[0]), (String) r[10]));
        }
        Trains t = new Trains(dep, arr, depName, arrName, d.toString(), runs, dates,
                "계획 시각: 시발·종착역은 코레일 운행계획, 중간역은 TAGO 열차 시간표의 역별 계획 시각입니다. "
                        + "TAGO 시간표가 없는 날의 중간역 운행은 계획 시각을 알 수 없어 '—'(확인 불가)로 두고 통계에서 뺍니다(추정하지 않음). "
                        + "차종은 TAGO 시간표에 적힌 그날의 배정 차종입니다. 통계는 기준일까지 최근 30일. 환승 경로는 다루지 않습니다.");
        if (ready) cache.put(cacheKey, t, Duration.ofMinutes(10));  // 시간표를 받는 중이면 캐시하지 않음
        return t;
    }

    public Punctuality punctuality(String dep, String arr, LocalDate from, LocalDate to, String groupBy, Integer thresholdMin) {
        if (to.isBefore(from)) throw ApiException.invalid("to 는 from 이후여야 합니다.");
        if (ChronoUnit.DAYS.between(from, to) > 366) throw ApiException.invalid("조회 기간은 최대 366일입니다.");
        if (dep.equals(arr)) throw ApiException.invalid("출발역과 도착역이 같습니다.");
        int thr = thresholdMin == null ? props.onTimeThresholdMin() : thresholdMin;
        if (thr < 0 || thr > 60) throw ApiException.invalid("thresholdMin 은 0~60 입니다.");
        String keyExpr = switch (groupBy) {
            case "train" -> "trn_no";
            // 공휴일(한국천문연구원 특일 정보)은 요일과 따로 'H' — 임시열차가 섞여 평소 요일과 다르다
            case "dow" -> "CASE WHEN EXISTS (SELECT 1 FROM ref.holiday h WHERE h.day = o.run_ymd) THEN 'H' "
                    + "ELSE extract(isodow FROM o.run_ymd)::int::text END";
            case "hour" -> "lpad(extract(hour FROM coalesce(o.plan_dep_at, o.act_dep_at))::int::text, 2, '0')";
            default -> throw ApiException.invalid("groupBy 는 train · dow · hour 중 하나입니다.");
        };
        // 과거 기간 결과는 새 운행 자료(하루 세 번)나 시간표가 들어오기 전까지 같다 → 10분 캐시 (시간표를 받는 중이면 캐시하지 않음)
        String cacheKey = "rail:punct:v2:%s:%s:%s:%s:%s:%d".formatted(dep, arr, from, to, groupBy, thr);
        Punctuality hit = cache.peek(cacheKey, Punctuality.class);
        if (hit != null) return hit;

        String depName = stationName(dep), arrName = stationName(arr);
        // 처음 보는 역 쌍은 2.5초까지만 기다리고 나머지는 뒤에서 받는다 (timetablePending → 화면이 다시 부름)
        boolean ready = timetable.ensure(dep, arr, timetable.runDates(dep, arr, from, to), Duration.ofMillis(2500));
        // 한 번의 계산으로 묶음별 행 + 전체 요약 행(GROUPING SETS 의 ()) — 역 쌍 운행 계산(od_trips)을 한 번만
        List<PunctualityItem> items = new ArrayList<>();
        List<Bucket> hist = new ArrayList<>();
        Summary[] summary = {new Summary(0, 0, 0, null, null, null)};
        jdbc.sql("""
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
                       count(*) FILTER (WHERE arr_delay_min > 30) AS b5
                FROM (SELECT o.*, {key} AS k FROM rail.od_trips_real(:a, :b, :f, :t) o) x
                GROUP BY GROUPING SETS ((k), ())""".replace("{key}", keyExpr))
                .param("thr", thr).param("a", dep).param("b", arr).param("f", from).param("t", to)
                .query(rs -> {
                    if (rs.getInt("total") == 1) {
                        String[] labels = {"≤0분", "1–5분", "6–10분", "11–20분", "21–30분", ">30분"};
                        for (int i = 0; i < 6; i++) hist.add(new Bucket(labels[i], rs.getInt("b" + i)));
                        summary[0] = new Summary(rs.getInt("samples"), rs.getInt("verified"),
                                rs.getInt("samples") - rs.getInt("verified"), round(rs, "rate", 3), round(rs, "avg_delay", 1),
                                round(rs, "p90", 1));
                    } else {
                        items.add(new PunctualityItem(rs.getString("k"), rs.getInt("samples"), rs.getInt("verified"),
                                round(rs, "rate", 3), round(rs, "avg_delay", 1), round(rs, "p90", 1), round(rs, "ride", 1),
                                null, "train".equals(groupBy) ? rs.getString("grade") : null));
                    }
                });
        if (hist.isEmpty()) for (String l : List.of("≤0분", "1–5분", "6–10분", "11–20분", "21–30분", ">30분")) hist.add(new Bucket(l, 0));
        items.sort("train".equals(groupBy)
                ? Comparator.comparingInt(PunctualityItem::samples).reversed().thenComparing(PunctualityItem::key)
                : Comparator.comparing(PunctualityItem::key));
        List<PunctualityItem> out = items;
        if ("train".equals(groupBy) && !items.isEmpty()) {
            Map<String, TrainMeta> meta = trainMeta(items.stream().map(PunctualityItem::key).toList(), from, to);
            out = items.stream().map(it -> new PunctualityItem(it.key(), it.samples(), it.verified(), it.onTimeRate(),
                    it.avgArrDelayMin(), it.p90ArrDelayMin(), it.avgRideMin(), meta.get(it.key()), it.grade())).toList();
        }
        Summary nation = jdbc.sql("""
                SELECT count(*) AS samples, count(arr_delay_min) AS verified,
                       avg(CASE WHEN arr_delay_min IS NULL THEN NULL WHEN arr_delay_min <= :thr THEN 1.0 ELSE 0.0 END) AS rate,
                       avg(arr_delay_min) AS avg_delay, percentile_cont(0.9) WITHIN GROUP (ORDER BY arr_delay_min) AS p90
                FROM rail.train_punctuality WHERE run_ymd BETWEEN :f AND :t""")
                .param("thr", thr).param("f", from).param("t", to)
                .query((rs, i) -> new Summary(rs.getInt("samples"), rs.getInt("verified"),
                        rs.getInt("samples") - rs.getInt("verified"), round(rs, "rate", 3), round(rs, "avg_delay", 1),
                        round(rs, "p90", 1))).single();
        Punctuality p = new Punctuality(dep, arr, depName, arrName, from.toString(), to.toString(), groupBy, thr, summary[0], out,
                hist, nation,
                Map.of("P-v1", "시발 출발·종착 도착을 코레일 운행계획과 정확 비교",
                        "P-t1", "중간역은 TAGO 열차 시간표의 역별 계획 시각과 정확 비교 (시간표가 없으면 확인 불가로 제외)"),
                "계획 시각(운행계획 · TAGO 시간표)과 운행정보(역별 실제 출발·도착)를 비교한 값. 계획 시각을 알 수 없는 열차는 "
                        + "'운행 확인 불가'로 정시율 분모에서 제외합니다. 직통 열차만 다룹니다(환승 제외)."
                        + ("dow".equals(groupBy) ? " 요일별의 H 는 공휴일(한국천문연구원 특일 정보)입니다." : ""), !ready);
        if (ready) cache.put(cacheKey, p, Duration.ofMinutes(10));
        return p;
    }

    /** 역 검색 — 이름 포함 검색, 최근 7일 운행 편수가 많은 역부터 */
    public List<Station> stations(String q, int limit) {
        return stations(q, limit, false);
    }

    /** byName = true 면 가나다순 (역 선택 목록), 아니면 정확·앞부분 일치 → 운행 편수 순 (검색 추천) */
    public List<Station> stations(String q, int limit, boolean byName) {
        LocalDate latest = latestDate().orElse(LocalDate.now(Times.KST));
        return jdbc.sql("""
                SELECT s.stn_cd, s.stn_nm, s.lat, s.lon, coalesce(c.n, 0) AS n
                FROM ref.station s
                LEFT JOIN (SELECT stn_cd, count(*) AS n FROM rail.run_info
                           WHERE run_ymd > :l::date - 7 AND run_ymd <= :l GROUP BY 1) c USING (stn_cd)
                WHERE (:q = '' OR s.stn_nm LIKE '%' || :q || '%') AND coalesce(c.n, 0) > 0
                ORDER BY {order} LIMIT :lim""".replace("{order}", byName ? "s.stn_nm COLLATE \"C\""   // UTF-8 바이트순 = 가나다순
                        : "(s.stn_nm = :q) DESC, (s.stn_nm LIKE :q || '%') DESC, n DESC, s.stn_nm"))
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
