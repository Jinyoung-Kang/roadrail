package com.roadrail.service;

import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.web.dto.OpsDtos.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 수집 상태 (FR-701, NFR-01/02/09). 완전성 95% 미만이면 warn. */
@Service
public class OpsService {
    public static final List<String> PROVIDERS = List.of("EX", "KORAIL", "KMA", "AIRKOREA", "KAKAO", "KAKAO_LOCAL");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");  // BASIC_ISO_DATE 는 오프셋(+0900)까지 붙인다
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final AppProperties props;

    public OpsService(JdbcClient jdbc, StringRedisTemplate redis, AppProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
    }

    public Status status() {
        OffsetDateTime now = Times.now();
        Map<String, Double> completeness = completeness();
        Map<String, Integer> gaps = new HashMap<>();
        jdbc.sql("""
                SELECT job_name, count(*) FROM ops.slot_gap
                WHERE backfilled_at IS NULL AND slot_ts > now() - interval '24 hours' AND reason <> 'SOURCE_EXPIRED'
                GROUP BY 1""").query(rs -> { gaps.put(rs.getString(1), rs.getInt(2)); });
        List<Job> jobs = jdbc.sql("""
                SELECT job_name, provider, cron, description, enabled, last_status, last_run_at, last_duration_ms,
                       last_calls, last_rows, last_message FROM ops.collect_job ORDER BY provider = '-', provider, job_name""")
                .query((rs, i) -> {
                    String job = rs.getString(1);
                    Double c = completeness.get(job);
                    boolean running = Boolean.TRUE.equals(safe(() -> redis.hasKey("rr:lock:" + job)));
                    String last = rs.getString(6);
                    boolean warn = (c != null && c < 0.95) || "FAILED".equals(last) || "SKIPPED_QUOTA".equals(last);
                    return new Job(job, rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), last,
                            Times.kst(rs.getObject(7, OffsetDateTime.class)), (Integer) rs.getObject(8),
                            (Integer) rs.getObject(9), (Integer) rs.getObject(10), rs.getString(11), c,
                            gaps.getOrDefault(job, c == null ? null : 0), running, warn);
                }).list();
        List<Run> runs = jdbc.sql("""
                SELECT run_id, job_name, trigger, started_at, finished_at, status, calls, rows, message
                FROM ops.job_run ORDER BY run_id DESC LIMIT 25""")
                .query((rs, i) -> new Run(rs.getLong(1), rs.getString(2), rs.getString(3),
                        Times.kst(rs.getObject(4, OffsetDateTime.class)), Times.kst(rs.getObject(5, OffsetDateTime.class)),
                        rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getString(9))).list();
        List<ApiError> errors = jdbc.sql("""
                SELECT called_at, provider, endpoint, http_status, error FROM ops.api_call
                WHERE called_at > now() - interval '24 hours' AND (error IS NOT NULL OR http_status IS DISTINCT FROM 200)
                ORDER BY called_at DESC LIMIT 10""")
                .query((rs, i) -> new ApiError(Times.kst(rs.getObject(1, OffsetDateTime.class)), rs.getString(2),
                        rs.getString(3), (Integer) rs.getObject(4), rs.getString(5))).list();
        List<Backfill> backfills = jdbc.sql("""
                SELECT backfill_id, provider, job_name, from_date::text, to_date::text, planned_calls, done_days, status,
                       requested_at, finished_at FROM ops.backfill ORDER BY requested_at DESC LIMIT 5""")
                .query((rs, i) -> new Backfill(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                        Times.kst(rs.getObject(9, OffsetDateTime.class)), Times.kst(rs.getObject(10, OffsetDateTime.class)))).list();
        // 공개 지연: 원본 행이 처음 저장된 시각 − 슬롯 시각 (도로공사 통행시간·교통량)
        List<Lag> lag = jdbc.sql("""
                SELECT s, percentile_cont(0.5) WITHIN GROUP (ORDER BY m) AS med,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY m) AS p90, count(*) AS n
                FROM (SELECT 'road_travel_time' AS s, extract(epoch FROM collected_at - slot_ts) / 60 AS m
                      FROM ts.road_travel_time
                      WHERE collected_at > now() - interval '24 hours'
                        -- 첫 대량 수집(하루치 한꺼번에)은 공개 지연이 아니므로 제외: 꼬리 수집으로 처음 본 행만
                        AND collected_at > (SELECT min(finished_at) + interval '5 minutes' FROM ops.job_run
                                            WHERE job_name = 'road_travel_time' AND status IN ('OK', 'PARTIAL'))
                      UNION ALL
                      SELECT 'road_volume_all', extract(epoch FROM collected_at - slot_ts) / 60
                      FROM ts.road_volume WHERE collected_at > now() - interval '24 hours') x
                GROUP BY s ORDER BY s""")
                .query((rs, i) -> new Lag(rs.getString("s"), RailService.round(rs, "med", 0), RailService.round(rs, "p90", 0),
                        rs.getInt("n"))).list();
        Map<String, Object> vol = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT (SELECT count(*) FROM ts.road_travel_time) AS road_rows, (SELECT count(*) FROM ts.road_corridor_tt) AS corridor_slots,
                       (SELECT count(*) FROM rail.run_info) AS run_info_rows, (SELECT count(DISTINCT run_ymd) FROM rail.run_plan) AS rail_days,
                       (SELECT min(run_ymd)::text FROM rail.run_plan) AS rail_from, (SELECT max(run_ymd)::text FROM rail.run_plan) AS rail_to,
                       (SELECT count(*) FROM env.weather_fcst) AS weather_rows, (SELECT count(*) FROM env.air_quality) AS air_rows,
                       (SELECT count(*) FROM ops.api_call WHERE called_at > now() - interval '24 hours') AS calls_24h""")
                .query(rs -> {
                    var md = rs.getMetaData();
                    for (int c = 1; c <= md.getColumnCount(); c++) vol.put(md.getColumnLabel(c), rs.getObject(c));
                });
        String hb = safe(() -> redis.opsForValue().get("rr:collector:heartbeat"));
        return new Status(now, hb != null, hb == null ? null : OffsetDateTime.parse(hb), jobs, quotas(), runs, errors,
                backfills, lag, vol);
    }

    /**
     * 작업별 24시간 완전성 = 저장된 슬롯 / 기대 슬롯.
     * 기대 슬롯의 시작은 max(24시간 전, 그 작업의 첫 수집) — 수집 시작 전 시간은 분모에 넣지 않는다.
     * 도로는 공개 워터마크(최신 슬롯) 기준 창을 쓰고, API 가 지난 날짜를 주지 않으므로 첫 수집일 0시부터 기대한다.
     */
    Map<String, Double> completeness() {
        Map<String, Double> m = new HashMap<>();
        jdbc.sql("""
                WITH wm AS (SELECT max(slot_ts) AS t, min(slot_ts) AS f FROM ts.road_corridor_tt),
                     series AS (SELECT count(DISTINCT (corridor_id, direction)) AS n FROM ref.corridor_road),
                     win AS (SELECT greatest(wm.t - interval '24 hours', date_trunc('day', wm.f)) AS s, wm.t FROM wm)
                SELECT count(*)::float / nullif((SELECT n FROM series)
                         * (extract(epoch FROM (SELECT t - s FROM win)) / 300 + 1), 0)
                FROM ts.road_corridor_tt, win WHERE slot_ts >= win.s AND slot_ts <= win.t""")
                .query(Double.class).optional().ifPresent(v -> m.put("road_travel_time", round3(Math.min(v, 1))));
        put(m, "road_volume_all", window("ts.road_volume", "slot_ts", 15, "count(DISTINCT slot_ts)"));
        put(m, "weather_vilage", window("env.weather_fcst", "base_at", 180, "count(DISTINCT base_at)"));
        put(m, "air_quality_sido", window("env.air_quality", "data_time", 60, "count(DISTINCT date_trunc('hour', data_time))"));
        put(m, "kakao_eta", window("ana.kakao_eta", "requested_at", 60, "count(DISTINCT date_trunc('hour', requested_at))"));
        put(m, "rail_daily", "SELECT count(*)::float FROM (SELECT 1 FROM rail.run_plan WHERE run_ymd = current_date - 1 LIMIT 1) x");
        return m;
    }

    /** 주기 intervalMin 인 시계열의 [max(24h 전, 첫 행), now] 창 완전성 SQL */
    private static String window(String table, String col, int intervalMin, String countExpr) {
        return """
                WITH w AS (SELECT greatest(now() - interval '24 hours', min(%2$s)) AS s FROM %1$s)
                SELECT least(%4$s::float / greatest(floor(extract(epoch FROM now() - (SELECT s FROM w)) / 60 / %3$d), 1), 1)
                FROM %1$s WHERE %2$s >= (SELECT s FROM w)""".formatted(table, col, intervalMin, countExpr);
    }

    private void put(Map<String, Double> m, String job, String sql) {
        Double v = jdbc.sql(sql).query(Double.class).optional().orElse(null);
        if (v != null) m.put(job, round3(v));
    }

    private static Double round3(Double v) { return v == null ? null : Math.round(v * 1000) / 1000.0; }

    public List<Quota> quotas() {
        String day = Times.now().format(YMD);
        List<Quota> out = new ArrayList<>();
        for (String p : PROVIDERS) {
            int limit = props.quotaOf(p);
            int total = parse(safe(() -> redis.opsForValue().get("quota:" + p + ":" + day)));
            int used = parse(safe(() -> redis.opsForValue().get("quota:used:" + p + ":" + day)));
            out.add(new Quota(p, Times.now().toLocalDate().toString(), limit, used, Math.max(total - used, 0),
                    Math.max(limit - total, 0)));
        }
        return out;
    }

    public int remaining(String provider) {
        String day = Times.now().format(YMD);
        int total = parse(safe(() -> redis.opsForValue().get("quota:" + provider + ":" + day)));
        return Math.max(props.quotaOf(provider) - total, 0);
    }

    private static int parse(String s) {
        try { return s == null ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    static <T> T safe(java.util.function.Supplier<T> s) {
        try { return s.get(); } catch (RuntimeException e) { return null; }
    }
}
