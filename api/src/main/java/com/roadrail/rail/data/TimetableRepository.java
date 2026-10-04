package com.roadrail.rail.data;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 역 쌍 · 날짜별 실제 시간표(TAGO) — rail.tt_plan(받은 시간표) · rail.tt_fetch(받은 기록). 언제 · 무엇을 받을지는
 * TimetableService 가 정한다.
 */
@Repository
public class TimetableRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public TimetableRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** TAGO 시간표의 열차 한 편 (grade = 차종, 없으면 null) */
    public record PlanRow(String trnNo, String grade, OffsetDateTime planDep, OffsetDateTime planArr) {}

    /** dates 가운데 받아 둔 날짜 — 완전하게 받았거나 12시간 안에 받은 것 */
    public List<LocalDate> fetchedDates(String dep, String arr, Collection<LocalDate> dates) {
        return jdbc.sql("""
                SELECT dep_date FROM rail.tt_fetch WHERE dep_stn_cd = :a AND arr_stn_cd = :b AND dep_date IN (:d)
                  AND (complete OR fetched_at > now() - interval '12 hours')""")
                .param("a", dep).param("b", arr).param("d", dates).query(LocalDate.class).list();
    }

    /** 운행 기록이 있는 날짜 (od_trips 를 부르기 전에 받을 날짜를 고른다) */
    public List<LocalDate> runDates(String dep, String arr, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT DISTINCT a.run_ymd FROM rail.run_info a
                WHERE a.stn_cd = :a AND a.run_ymd BETWEEN :f AND :t AND a.dep_at IS NOT NULL
                  AND EXISTS (SELECT 1 FROM rail.run_info b WHERE b.run_ymd = a.run_ymd AND b.trn_no = a.trn_no
                              AND b.run_seq > a.run_seq AND b.stn_cd = :b)""")
                .param("a", dep).param("b", arr).param("f", from).param("t", to).query(LocalDate.class).list();
    }

    /** 받은 시간표를 저장 — 같은 역 쌍 · 날짜 · 열차는 새 값으로 덮어쓴다 */
    public void savePlans(String dep, String arr, LocalDate date, List<PlanRow> plans) {
        List<Map<String, Object>> rows = plans.stream().map(p -> Map.<String, Object>of("no", p.trnNo(),
                "dep", p.planDep().toString(), "arr", p.planArr().toString(), "grade", p.grade() == null ? "" : p.grade())).toList();
        jdbc.sql("""
                INSERT INTO rail.tt_plan (dep_stn_cd, arr_stn_cd, dep_date, trn_no, plan_dep_at, plan_arr_at, grade)
                SELECT :a, :b, :d, r.no, r.dep, r.arr, nullif(r.grade, '')
                FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS r(no text, dep timestamptz, arr timestamptz, grade text)
                ON CONFLICT (dep_stn_cd, arr_stn_cd, dep_date, trn_no) DO UPDATE
                  SET plan_dep_at = EXCLUDED.plan_dep_at, plan_arr_at = EXCLUDED.plan_arr_at, grade = EXCLUDED.grade,
                      fetched_at = now()""")
                .param("a", dep).param("b", arr).param("d", date).param("rows", mapper.writeValueAsString(rows)).update();
    }

    /** 그날 코레일 역 쌍 운행 편수 (rail.od_trips) */
    public int odTripCount(String dep, String arr, LocalDate date) {
        return jdbc.sql("SELECT count(*) FROM rail.od_trips(:a, :b, :d, :d)")
                .param("a", dep).param("b", arr).param("d", date).query(Integer.class).single();
    }

    /** 받은 기록 — trains = 받은 편수(조회 불가면 null), complete = 다시 받지 않아도 되는지 */
    public void recordFetch(String dep, String arr, LocalDate date, Integer trains, boolean complete) {
        jdbc.sql("""
                INSERT INTO rail.tt_fetch (dep_stn_cd, arr_stn_cd, dep_date, trains, complete) VALUES (:a, :b, :d, :n, :c)
                ON CONFLICT (dep_stn_cd, arr_stn_cd, dep_date) DO UPDATE
                  SET trains = EXCLUDED.trains, complete = EXCLUDED.complete, fetched_at = now()""")
                .param("a", dep).param("b", arr).param("d", date).param("n", trains).param("c", complete).update();
    }
}
