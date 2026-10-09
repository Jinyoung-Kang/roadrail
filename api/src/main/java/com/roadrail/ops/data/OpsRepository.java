package com.roadrail.ops.data;

import com.roadrail.ops.model.OpsDtos.*;
import com.roadrail.shared.Rows;
import com.roadrail.shared.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 수집 상태 · 관리 명령 조회 — 작업 · 실행 이력 · 결측 · 호출 기록 · 백필 · 공개 지연 · 저장 규모. 쿼리와 행 매핑만 맡는다 */
@Repository
public class OpsRepository {
    private final JdbcClient jdbc;

    public OpsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean ping() {
        return jdbc.sql("SELECT 1").query(Integer.class).single() == 1;
    }

    /** 작업별 24시간 미해결 결측 — 수집 결측과 원천 표본 없음(NO_SAMPLES)을 따로 */
    public record GapCounts(Map<String, Integer> gaps, Map<String, Integer> noSamples) {}

    public GapCounts openGaps24h() {
        Map<String, Integer> gaps = new HashMap<>(), noSamples = new HashMap<>();
        jdbc.sql("""
                SELECT job_name, count(*) FILTER (WHERE reason <> 'NO_SAMPLES'), count(*) FILTER (WHERE reason = 'NO_SAMPLES')
                FROM ops.slot_gap
                WHERE backfilled_at IS NULL AND slot_ts > now() - interval '24 hours' AND reason <> 'SOURCE_EXPIRED'
                GROUP BY 1""").query(rs -> {
            gaps.put(rs.getString(1), rs.getInt(2));
            noSamples.put(rs.getString(1), rs.getInt(3));
        });
        return new GapCounts(gaps, noSamples);
    }

    public List<String> jobNames() {
        return jdbc.sql("SELECT job_name FROM ops.collect_job").query(String.class).list();
    }

    /** 수집 작업의 설정 · 마지막 실행 */
    public record JobRow(String job, String provider, String cron, String description, boolean enabled, String lastStatus,
                         OffsetDateTime lastRunAt, Integer lastDurationMs, Integer lastCalls, Integer lastRows, String lastMessage) {}

    public List<JobRow> jobs() {
        return jdbc.sql("""
                SELECT job_name, provider, cron, description, enabled, last_status, last_run_at, last_duration_ms,
                       last_calls, last_rows, last_message FROM ops.collect_job ORDER BY provider = '-', provider, job_name""")
                .query((rs, i) -> new JobRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        rs.getString(6), Times.kst(rs.getObject(7, OffsetDateTime.class)), (Integer) rs.getObject(8),
                        (Integer) rs.getObject(9), (Integer) rs.getObject(10), rs.getString(11))).list();
    }

    public List<Run> recentRuns() {
        return jdbc.sql("""
                SELECT run_id, job_name, trigger, started_at, finished_at, status, calls, rows, message
                FROM ops.job_run ORDER BY run_id DESC LIMIT 25""")
                .query((rs, i) -> new Run(rs.getLong(1), rs.getString(2), rs.getString(3),
                        Times.kst(rs.getObject(4, OffsetDateTime.class)), Times.kst(rs.getObject(5, OffsetDateTime.class)),
                        rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getString(9))).list();
    }

    public List<ApiError> recentApiErrors() {
        return jdbc.sql("""
                SELECT called_at, provider, endpoint, http_status, error FROM ops.api_call
                WHERE called_at > now() - interval '24 hours' AND (error IS NOT NULL OR http_status IS DISTINCT FROM 200)
                ORDER BY called_at DESC LIMIT 10""")
                .query((rs, i) -> new ApiError(Times.kst(rs.getObject(1, OffsetDateTime.class)), rs.getString(2),
                        rs.getString(3), (Integer) rs.getObject(4), rs.getString(5))).list();
    }

    public List<Backfill> recentBackfills() {
        return jdbc.sql("""
                SELECT backfill_id, provider, job_name, from_date::text, to_date::text, planned_calls, done_days, status,
                       requested_at, finished_at FROM ops.backfill ORDER BY requested_at DESC LIMIT 5""")
                .query((rs, i) -> new Backfill(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                        Times.kst(rs.getObject(9, OffsetDateTime.class)), Times.kst(rs.getObject(10, OffsetDateTime.class)))).list();
    }

    /** 계열별 가장 최근 슬롯 (도로공사 통행시간 · 교통량) — 계열 이름 → 시각 (행이 없으면 빠짐) */
    public Map<String, OffsetDateTime> latestSlots() {
        Map<String, OffsetDateTime> m = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT 'road_travel_time' AS s, (SELECT max(slot_ts) FROM ts.road_travel_time) AS t
                UNION ALL SELECT 'road_volume_all', (SELECT max(slot_ts) FROM ts.road_volume)""")
                .query(rs -> {
                    OffsetDateTime t = rs.getObject("t", OffsetDateTime.class);
                    if (t != null) m.put(rs.getString("s"), Times.kst(t));
                });
        return m;
    }

    /** 공개 지연: 원본 행이 처음 저장된 시각 − 슬롯 시각 (도로공사 통행시간·교통량) */
    public List<Lag> publishLag() {
        return jdbc.sql("""
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
                .query((rs, i) -> new Lag(rs.getString("s"), Rows.round(rs, "med", 0), Rows.round(rs, "p90", 0),
                        rs.getInt("n"))).list();
    }

    /** 저장 규모 — 전체 행 수를 정확히 센다 */
    public Map<String, Object> volumes() {
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
        return vol;
    }

    /**
     * 최근 24시간 오류 실행 (작업별 최신 3건, 최대 20건). 전체 내용은 수집기가 실행 끝에 ops.job_run.detail 에 남긴다.
     * detail 이 없는 실행(V8 이전)은 메시지와 그 시간대의 실패한 외부 호출로 만든다. 해결 안 된 것부터.
     */
    public List<Failure> failures() {
        return jdbc.sql("""
                SELECT * FROM (
                  SELECT r.run_id, r.job_name, r.trigger, r.started_at, r.finished_at, r.status, r.message,
                         coalesce(r.detail, concat_ws(E'\n',
                           format('작업: %s · 트리거: %s · 상태: %s', r.job_name, r.trigger, r.status),
                           format('호출 %s건 · 저장 %s행', r.calls, r.rows),
                           '메시지: ' || coalesce(r.message, '-'),
                           (SELECT E'\n[이 시간대의 실패한 외부 호출]\n' || string_agg(format('%s %s %s · HTTP %s · %s',
                                     to_char(c.called_at AT TIME ZONE 'Asia/Seoul', 'HH24:MI:SS'), c.provider, c.endpoint,
                                     coalesce(c.http_status::text, '-'), coalesce(c.error, '-')), E'\n' ORDER BY c.called_at)
                            FROM (SELECT * FROM ops.api_call c
                                  WHERE c.called_at BETWEEN r.started_at AND coalesce(r.finished_at, r.started_at + interval '1 hour')
                                    AND (c.error IS NOT NULL OR c.http_status IS DISTINCT FROM 200)
                                  ORDER BY c.called_at LIMIT 50) c))) AS detail,
                         (SELECT min(ok.finished_at) FROM ops.job_run ok
                          WHERE ok.job_name = r.job_name AND ok.run_id > r.run_id AND ok.status = 'OK') AS resolved_at,
                         row_number() OVER (PARTITION BY r.job_name ORDER BY r.run_id DESC) AS rn
                  FROM ops.job_run r
                  WHERE r.started_at > now() - interval '24 hours' AND r.status IN ('FAILED', 'PARTIAL', 'SKIPPED_QUOTA')) x
                WHERE rn <= 3 ORDER BY resolved_at IS NOT NULL, run_id DESC LIMIT 20""")
                .query((rs, i) -> new Failure(rs.getLong("run_id"), rs.getString("job_name"), rs.getString("trigger"),
                        Times.kst(rs.getObject("started_at", OffsetDateTime.class)),
                        Times.kst(rs.getObject("finished_at", OffsetDateTime.class)), rs.getString("status"),
                        rs.getString("message"), rs.getString("detail"),
                        Times.kst(rs.getObject("resolved_at", OffsetDateTime.class)))).list();
    }

    /** 길 통행시간의 24시간 완전성(원천 표본 없음은 분모에서 뺌) — 값이 없으면 빈 값 */
    public Optional<Double> roadCompleteness() {
        return jdbc.sql("""
                WITH wm AS (SELECT max(slot_ts) AS t, min(slot_ts) AS f FROM ts.road_corridor_tt),
                     series AS (SELECT count(DISTINCT (corridor_id, direction)) AS n FROM ref.corridor_road),
                     win AS (SELECT greatest(wm.t - interval '24 hours', date_trunc('day', wm.f)) AS s, wm.t FROM wm),
                     empty AS (SELECT count(*) AS n FROM ops.slot_gap, win
                               WHERE job_name = 'road_travel_time' AND reason = 'NO_SAMPLES' AND backfilled_at IS NULL
                                 AND slot_ts >= win.s AND slot_ts <= win.t)
                SELECT count(*)::float / nullif((SELECT n FROM series)
                         * (extract(epoch FROM (SELECT t - s FROM win)) / 300 + 1) - (SELECT n FROM empty), 0)
                FROM ts.road_corridor_tt, win WHERE slot_ts >= win.s AND slot_ts <= win.t""")
                .query(Double.class).optional();
    }

    /** 주기 intervalMin 인 시계열의 창 완전성 */
    public Optional<Double> windowCompleteness(String table, String col, int intervalMin, String countExpr) {
        return jdbc.sql(window(table, col, intervalMin, countExpr)).query(Double.class).optional();
    }

    /** 어제 운행계획이 들어왔나 (0 또는 1) */
    public Optional<Double> railDailyCompleteness() {
        return jdbc.sql("SELECT count(*)::float FROM (SELECT 1 FROM rail.run_plan WHERE run_ymd = current_date - 1 LIMIT 1) x")
                .query(Double.class).optional();
    }

    /** 주기 intervalMin 인 시계열의 [max(24h 전, 첫 행), now] 창 완전성 SQL */
    private static String window(String table, String col, int intervalMin, String countExpr) {
        return """
                WITH w AS (SELECT greatest(now() - interval '24 hours', min(%2$s)) AS s FROM %1$s)
                SELECT least(%4$s::float / greatest(floor(extract(epoch FROM now() - (SELECT s FROM w)) / 60 / %3$d), 1), 1)
                FROM %1$s WHERE %2$s >= (SELECT s FROM w)""".formatted(table, col, intervalMin, countExpr);
    }

    public boolean jobExists(String job) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ops.collect_job WHERE job_name = :j)").param("j", job)
                .query(Boolean.class).single();
    }

    /**
     * 겹치는 기간의 백필이 대기 · 실행 중이 아니면 등록하고 true. 동시에 들어온 같은 요청이 둘 다 등록되지 않게
     * 한 트랜잭션에서 자문 잠금(advisory lock)을 잡고 확인 · 등록한다 (QA-05).
     */
    @Transactional
    public boolean insertBackfillIfFree(String id, LocalDate from, LocalDate to, int planned) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('ops.backfill'))").query(Object.class).single();
        return jdbc.sql("""
                INSERT INTO ops.backfill (backfill_id, provider, job_name, from_date, to_date, planned_calls)
                SELECT :id, 'KORAIL', 'rail_daily', :f, :t, :p
                WHERE NOT EXISTS (SELECT 1 FROM ops.backfill
                                  WHERE status IN ('QUEUED', 'RUNNING') AND from_date <= :t AND to_date >= :f)""")
                .param("id", id).param("f", from).param("t", to).param("p", planned).update() == 1;
    }
}
