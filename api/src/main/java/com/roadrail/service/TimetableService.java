package com.roadrail.service;

import com.roadrail.common.Times;
import com.roadrail.external.TagoTrainClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/**
 * 역 쌍 · 날짜별 실제 시간표(TAGO) 를 rail.tt_plan 에 채운다 — 한 번 받은 역 쌍 · 날짜는 rail.tt_fetch 에 남겨 다시 부르지 않는다.
 * 지난 날짜만 받는다(당일 · 미래는 TAGO 에 시간표가 없거나 일부만 있음, 2026-09-25 실측).
 * 지난 날짜도 비어 있는 날이 있어, 받은 편수가 코레일 운행 편수의 80% 미만이면 불완전으로 두고 12시간 뒤 다시 받는다.
 * 같은 역 쌍 · 날짜를 여러 요청이 동시에 원하면 호출은 한 번만 (in-flight 공유), 동시 호출은 최대 6건.
 */
@Service
public class TimetableService {
    private static final Logger log = LoggerFactory.getLogger(TimetableService.class);
    private final JdbcClient jdbc;
    private final TagoTrainClient tago;
    private final ObjectMapper mapper;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore gate = new Semaphore(6);
    private final Map<String, CompletableFuture<Void>> inflight = new ConcurrentHashMap<>();

    public TimetableService(JdbcClient jdbc, TagoTrainClient tago, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tago = tago;
        this.mapper = mapper;
    }

    /**
     * dep → arr 의 dates 시간표가 DB 에 있게 한다. wait 까지만 기다리고(0 이면 기다리지 않음) 나머지는 뒤에서 계속 받는다.
     * 반환: 기다린 뒤 모든 날짜가 준비됐는지.
     */
    public boolean ensure(String dep, String arr, Collection<LocalDate> dates, Duration wait) {
        if (!tago.enabled() || dates.isEmpty()) return true;
        LocalDate today = Times.now().toLocalDate();
        List<LocalDate> past = dates.stream().filter(d -> d.isBefore(today)).distinct().toList();
        if (past.isEmpty()) return true;
        Set<LocalDate> have = new HashSet<>(jdbc.sql("""
                SELECT dep_date FROM rail.tt_fetch WHERE dep_stn_cd = :a AND arr_stn_cd = :b AND dep_date IN (:d)
                  AND (complete OR fetched_at > now() - interval '12 hours')""")
                .param("a", dep).param("b", arr).param("d", past).query(LocalDate.class).list());
        List<CompletableFuture<Void>> todo = new ArrayList<>();
        for (LocalDate d : past) {
            if (have.contains(d)) continue;
            String key = dep + ">" + arr + "@" + d;
            todo.add(inflight.computeIfAbsent(key, k -> CompletableFuture.runAsync(() -> fetch(dep, arr, d), exec)
                    .whenComplete((v, e) -> inflight.remove(k))));
        }
        if (todo.isEmpty()) return true;
        if (wait.isZero()) return false;
        try {
            CompletableFuture.allOf(todo.toArray(CompletableFuture[]::new)).get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
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

    private void fetch(String dep, String arr, LocalDate date) {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            String depNode = tago.nodeId(name(dep)), arrNode = tago.nodeId(name(arr));
            if (depNode == null || arrNode == null) {
                if (!tago.nodes().isEmpty()) record(dep, arr, date, null, true);  // TAGO 에 없는 역 → 조회 불가로 기록
                return;
            }
            var plans = tago.plans(depNode, arrNode, date);
            if (plans == null) return;  // 호출 실패 → 기록하지 않고 다음에 다시
            if (!plans.isEmpty()) {
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
            int korail = jdbc.sql("SELECT count(*) FROM rail.od_trips(:a, :b, :d, :d)")
                    .param("a", dep).param("b", arr).param("d", date).query(Integer.class).single();
            record(dep, arr, date, plans.size(), plans.size() >= 0.8 * korail);
        } catch (RuntimeException e) {
            log.warn("시간표 저장 실패 {}→{} {}: {}", dep, arr, date, e.getMessage());
        } finally {
            gate.release();
        }
    }

    private void record(String dep, String arr, LocalDate date, Integer n, boolean complete) {
        jdbc.sql("""
                INSERT INTO rail.tt_fetch (dep_stn_cd, arr_stn_cd, dep_date, trains, complete) VALUES (:a, :b, :d, :n, :c)
                ON CONFLICT (dep_stn_cd, arr_stn_cd, dep_date) DO UPDATE
                  SET trains = EXCLUDED.trains, complete = EXCLUDED.complete, fetched_at = now()""")
                .param("a", dep).param("b", arr).param("d", date).param("n", n).param("c", complete).update();
    }

    private String name(String code) {
        return jdbc.sql("SELECT stn_nm FROM ref.station WHERE stn_cd = :c").param("c", code).query(String.class).optional().orElse(null);
    }
}
