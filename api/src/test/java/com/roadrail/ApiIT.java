package com.roadrail;

import com.roadrail.env.app.EnvService;
import com.roadrail.support.IntegrationTest;
import com.roadrail.env.model.EnvDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 시드 DB 로 API 계약 확인 (10장 API·E2E) — 판단 카드 · 오류 규약 · 관리 API 401/409/429 · 캐시. */
@AutoConfigureMockMvc
class ApiIT extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    @Autowired
    MockMvc mvc;
    @Autowired
    EnvService env;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE ref.corridor, ref.toll_unit, ref.station CASCADE");
        jdbc.execute("TRUNCATE ts.road_corridor_tt, ana.road_baseline, rail.run_plan, rail.run_info, rail.train_punctuality, ops.backfill, ref.rail_link, ops.job_run, ref.holiday");
        jdbc.execute("""
                INSERT INTO ref.toll_unit (unit_code, unit_name, route_no, route_name, lat, lon) VALUES
                  ('101', '서울', '001', '경부선', 37.365, 127.102), ('115', '대전', '001', '경부선', 36.361, 127.448);
                INSERT INTO ref.station (stn_cd, stn_nm, lat, lon, source) VALUES
                  ('S1', '서울', 37.55, 126.97, 'KAKAO'), ('S2', '대전', 36.33, 127.43, 'KAKAO');
                INSERT INTO ref.corridor (corridor_id, name, origin_city, dest_city) VALUES ('SEL-DJN', '서울–대전', '서울', '대전');
                INSERT INTO ref.corridor_road VALUES ('SEL-DJN', 'DN', 1, '101', '115', 139.0), ('SEL-DJN', 'UP', 1, '115', '101', 139.0);
                INSERT INTO ref.corridor_rail VALUES ('SEL-DJN', 'DN', 'S1', 'S2'), ('SEL-DJN', 'UP', 'S2', 'S1');
                INSERT INTO ref.corridor_env_point VALUES ('SEL-DJN', 'origin', '서울', 37.55, 126.97, 60, 126, '서울'),
                                                         ('SEL-DJN', 'dest', '대전', 36.33, 127.43, 68, 100, '대전');
                INSERT INTO ref.rail_link (dep_stn_cd, arr_stn_cd, path, length_km, straight_km)
                  VALUES ('S1', 'S2', '[[37.55, 126.97], [36.9, 127.2], [36.33, 127.43]]', 150.0, 140.0);
                INSERT INTO ops.job_run (job_name, trigger, started_at, finished_at, status, message, detail)
                  VALUES ('road_travel_time', 'SCHEDULE', now() - interval '1 hour', now() - interval '59 minutes', 'FAILED',
                          'ProviderError: HTTP 500', E'작업: road_travel_time\n[스택 트레이스]\nTraceback …');
                """);
        // 최근 도로 관측 (1시간 전 슬롯) + 전체 요일 기준선
        OffsetDateTime slot = OffsetDateTime.now(KST).withSecond(0).withNano(0).minusHours(1);
        slot = slot.withMinute(slot.getMinute() - slot.getMinute() % 5);
        jdbc.update("INSERT INTO ts.road_corridor_tt VALUES (?, 'SEL-DJN', 'DN', 7260, 10, 10, 'OK', now())", slot);
        jdbc.execute("""
                INSERT INTO ana.road_baseline (corridor_id, direction, dow, slot_idx, p50_sec, p90_sec, n, window_from, window_to)
                SELECT 'SEL-DJN', 'DN', 0, s, 5580, 6400, 20, current_date - 56, current_date FROM generate_series(0, 287) s""");
        // 공휴일 달력: 오늘 · 지난주 같은 요일 (한국천문연구원 특일 정보 형식)
        jdbc.update("INSERT INTO ref.holiday (day, name) VALUES (?, '테스트휴일'), (?, '지난휴일')",
                LocalDate.now(KST), LocalDate.now(KST).minusDays(7));
        // 같은 요일 지난주 열차 1편 S1 23:59 → S2 00:59(다음 날 아님: 23:59 출발 · 60분) — 운행계획 · 운행정보 · 정시성 원본
        LocalDate ref = LocalDate.now(KST).minusDays(7);
        OffsetDateTime dep = ref.atTime(23, 58).atZone(KST).toOffsetDateTime();
        jdbc.update("INSERT INTO rail.run_plan (run_ymd, trn_no, dep_stn_cd, arr_stn_cd, plan_dep_at, plan_arr_at) VALUES (?, '00199', 'S1', 'S2', ?, ?)",
                ref, dep, dep.plusMinutes(57));
        jdbc.update("INSERT INTO rail.run_info (run_ymd, trn_no, run_seq, stn_cd, stn_nm, dep_at) VALUES (?, '00199', 1, 'S1', '서울', ?)",
                ref, dep.plusMinutes(1));
        jdbc.update("INSERT INTO rail.run_info (run_ymd, trn_no, run_seq, stn_cd, stn_nm, arr_at) VALUES (?, '00199', 2, 'S2', '대전', ?)",
                ref, dep.plusMinutes(61));
        jdbc.update("""
                INSERT INTO rail.train_punctuality (run_ymd, trn_no, dep_stn_cd, arr_stn_cd, plan_dep_at, plan_arr_at, act_dep_at,
                  act_arr_at, dep_delay_min, arr_delay_min, on_time, status, calc_rule, threshold_min)
                VALUES (?, '00199', 'S1', 'S2', ?, ?, ?, ?, 1, 4, true, 'OK', 'P-v1', 5)""",
                ref, dep, dep.plusMinutes(57), dep.plusMinutes(1), dep.plusMinutes(61));
    }

    @Test
    void corridorIncidentListShowsExpresswayMessagesOnly() throws Exception {
        // UTIC(일반 도로 포함)는 판단 카드의 경로 주변 돌발에만 — 길 화면의 돌발 목록은 도로공사 문자 그대로
        jdbc.update("DELETE FROM ts.road_incident");
        jdbc.update("""
                INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, route_name, content, corridor_ids, source)
                VALUES (repeat('a', 64), now(), '1', '사고', '경부선', '도로공사 문자', '{SEL-DJN}', 'EX'),
                       (repeat('b', 64), now(), 'U1', '사고', '경부고속도로', 'UTIC 돌발', '{SEL-DJN}', 'UTIC')""");
        mvc.perform(get("/api/v1/incidents").param("corridorId", "SEL-DJN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].source").value("EX"));
    }

    @Test
    void routeIncidentsAreAllReturnedNewestFirstWithinEachSourcesDistance() {
        // 서울역에서 남쪽으로 약 11km 경로 위 도로공사 12건 · UTIC 12건 — 예전 상한(8건)에 잘리지 않고 전부, 최근 순
        jdbc.update("DELETE FROM ts.road_incident");
        jdbc.update("""
                INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, route_name, content, lat, lon, source)
                SELECT md5('ex' || g) || md5('ex' || g), now() - g * interval '1 minute', '1', '사고', '경부선', '도로공사 ' || g,
                       37.545 - (g - 1) * 0.005, 126.97, 'EX' FROM generate_series(1, 12) g""");
        jdbc.update("""
                INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, route_name, content, lat, lon, source)
                SELECT md5('ut' || g) || md5('ut' || g), now() - (12 + g) * interval '1 minute', 'U1', '사고', '한강대로', 'UTIC ' || g,
                       37.48 - (g - 1) * 0.0025, 126.9705, 'UTIC' FROM generate_series(1, 12) g""");
        jdbc.update("""
                INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, route_name, content, lat, lon, source, last_seen_at)
                VALUES (repeat('c', 64), now(), 'U1', '사고', '옆길', 'UTIC 옆길 0.7km', 37.47, 126.978, 'UTIC', now()),
                       (repeat('d', 64), now(), 'U1', '사고', '경부고속도로', 'UTIC 도로공사와 같은 돌발', 37.522, 126.97, 'UTIC', now()),
                       (repeat('e', 64), now(), '1', '사고', '경부선', '도로공사 1.5km', 37.50, 126.987, 'EX', now()),
                       (repeat('f', 64), now(), 'U1', '사고', '한강대로', 'UTIC 끝난 돌발', 37.46, 126.97, 'UTIC', now() - interval '1 hour')""");
        try {
            var got = env.routeIncidents(List.of(new double[]{37.55, 126.97}, new double[]{37.45, 126.97}), null);
            assertThat(got).hasSize(25);
            assertThat(got).filteredOn(i -> "UTIC".equals(i.source())).hasSize(12);
            assertThat(got).extracting(EnvDtos.Incident::content)
                    .contains("도로공사 1.5km")                                               // 도로공사 문자는 2km 안
                    .doesNotContain("UTIC 옆길 0.7km", "UTIC 도로공사와 같은 돌발", "UTIC 끝난 돌발");  // UTIC 0.5km · 겹침 · 끝난 것
            assertThat(got).isSortedAccordingTo(Comparator.comparing(EnvDtos.Incident::sentAt).reversed());
        } finally {
            jdbc.update("DELETE FROM ts.road_incident");
        }
    }

    @Test
    void corridorsList() throws Exception {
        mvc.perform(get("/api/v1/corridors")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("SEL-DJN"))
                .andExpect(jsonPath("$[0].road.DN.units[0].name").value("서울"))
                .andExpect(jsonPath("$[0].rail.DN.arr.name").value("대전"));
    }

    @Test
    void nowCardHasEvidenceFreshnessAndCaches() throws Exception {
        mvc.perform(get("/api/v1/corridors/SEL-DJN/now").param("dir", "DN").param("departIn", "0"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.road.travelSec").value(7260))
                .andExpect(jsonPath("$.road.baselineP50Sec").value(5580))
                .andExpect(jsonPath("$.road.vsBaselinePct").value(30.1))  // 기준선 n=20 ≥ 4
                .andExpect(jsonPath("$.road.forecast", hasSize(3)))
                .andExpect(jsonPath("$.decision.rule").value("R-DEC-01"))
                .andExpect(jsonPath("$.decision.reasons", not(empty())))
                .andExpect(jsonPath("$.freshness.road", containsString("공개 지연")))
                // 철도 자료 시각 안내가 실제 수집 일정(05:30 · 09:30 · 15:30)과 다르던 문구 '매일 03:30 계산' (BUG-08)
                .andExpect(jsonPath("$.freshness.rail", allOf(containsString("운행 기준 시간표"), not(containsString("03:30")))))
                .andExpect(jsonPath("$.caveat", containsString("20분")))
                .andExpect(jsonPath("$.asOf", endsWith("+09:00")));
        mvc.perform(get("/api/v1/corridors/SEL-DJN/now").param("dir", "DN"))
                .andExpect(header().string("X-Cache", "HIT"));
    }

    @Test
    void errorsFollowContract() throws Exception {
        mvc.perform(get("/api/v1/corridors/NOPE/now").param("dir", "DN")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRIDOR_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId", hasLength(26)));
        mvc.perform(get("/api/v1/corridors/SEL-DJN/now").param("dir", "XX")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/corridors/SEL-DJN/now").param("dir", "DN").param("departIn", "999"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rail/punctuality").param("corridorId", "SEL-DJN").param("from", "2025-01-01")
                .param("to", "2026-09-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("366")));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void standardMvcErrorsFollowTheErrorContract(CapturedOutput output) throws Exception {
        // 클라이언트 오류가 500 · 스택 트레이스 ERROR 로그로 남던 것 (BUG-02, 재현: text/plain → 500, Accept: text/csv → 빈 406)
        mvc.perform(post("/api/v1/admin/backfill").header("X-Admin-Token", ADMIN).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.traceId", hasLength(26)));
        mvc.perform(get("/api/v1/corridors").accept("text/csv")).andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));  // JSON 을 받지 않는 클라이언트에게는 본문 없이
        org.assertj.core.api.Assertions.assertThat(output).doesNotContain("처리되지 않은 오류").doesNotContain("Failure in @ExceptionHandler");
    }

    @Test
    void adminRequiresTokenAndChecksLockAndBudget() throws Exception {
        mvc.perform(post("/api/v1/admin/jobs/road_travel_time/run")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/admin/jobs/road_travel_time/run").header("X-Admin-Token", ADMIN))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
        org.assertj.core.api.Assertions.assertThat(redis.opsForStream().size("rr:commands")).isEqualTo(1L);

        redis.opsForValue().set("rr:lock:road_travel_time", "x");
        mvc.perform(post("/api/v1/admin/jobs/road_travel_time/run").header("X-Admin-Token", ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_RUNNING"));

        LocalDate y = LocalDate.now(KST).minusDays(1);
        String body = "{\"provider\":\"KORAIL\",\"job\":\"rail_daily\",\"from\":\"%s\",\"to\":\"%s\"}";
        mvc.perform(post("/api/v1/admin/backfill").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(y.minusDays(9), y)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.plannedCalls").value(30))
                .andExpect(jsonPath("$.budgetOk").value(true));
        mvc.perform(post("/api/v1/admin/backfill").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(y.minusDays(200), y)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("3개월")));
        String day = LocalDate.now(KST).toString().replace("-", "");
        redis.opsForValue().set("quota:KORAIL:" + day, "8990");
        mvc.perform(post("/api/v1/admin/backfill").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(y.minusDays(9), y)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("QUOTA_EXHAUSTED"));
    }

    @Test
    void collectStatusAndHealth() throws Exception {
        // 잠금 · 예산은 MGET 한 번으로 읽는다 — 위치(작업 · 공급자)가 어긋나지 않는지 (PERF-01 · PERF-07)
        String day = LocalDate.now(KST).toString().replace("-", "");
        redis.opsForValue().set("rr:lock:road_travel_time", "x");
        redis.opsForValue().set("quota:KASI:" + day, "5");
        redis.opsForValue().set("quota:used:KASI:" + day, "3");
        mvc.perform(get("/api/v1/ops/collect-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[?(@.job == 'road_travel_time')].running").value(hasItem(true)))
                .andExpect(jsonPath("$.jobs[?(@.job == 'rail_daily')].running").value(hasItem(false)))
                .andExpect(jsonPath("$.quota[?(@.provider == 'KASI')].used").value(hasItem(3)))
                .andExpect(jsonPath("$.quota[?(@.provider == 'KASI')].reserved").value(hasItem(2)))
                .andExpect(jsonPath("$.quota[?(@.provider == 'KASI')].remaining").value(hasItem(95)))
                .andExpect(jsonPath("$.volumes.measured_at").isNotEmpty())
                .andExpect(jsonPath("$.jobs[?(@.job == 'road_travel_time')].cron").value(hasItem("*/10 * * * *")))
                .andExpect(jsonPath("$.quota[?(@.provider == 'AIRKOREA')].limit").value(hasItem(450)))
                .andExpect(jsonPath("$.failures[0].job").value("road_travel_time"))
                .andExpect(jsonPath("$.failures[0].status").value("FAILED"))
                // 공개 경로는 오류 상세(메시지 · 스택 트레이스 · 외부 호출)를 비운다 — 전체는 관리 토큰으로 /admin/collect-status (ADR-028)
                .andExpect(jsonPath("$.failures[0].detail").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.failures[0].message").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.detailed").value(false))
                .andExpect(jsonPath("$.collectorAlive").value(false));
        mvc.perform(get("/api/v1/admin/collect-status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/collect-status").header("X-Admin-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/collect-status").header("X-Admin-Token", ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.detailed").value(true))
                .andExpect(jsonPath("$.failures[0].detail").value(org.hamcrest.Matchers.containsString("[스택 트레이스]")))
                .andExpect(jsonPath("$.jobs[?(@.job == 'road_travel_time')].running").value(hasItem(true)));
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db").value("UP"))
                .andExpect(jsonPath("$.components.collector").value("DOWN"))
                .andExpect(jsonPath("$.status").value("DEGRADED"));
    }

    @Test
    void railOdAndStations() throws Exception {
        LocalDate ref = LocalDate.now(KST).minusDays(7);
        mvc.perform(get("/api/v1/rail/od/trains").param("dep", "S1").param("arr", "S2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value(ref.toString()))
                .andExpect(jsonPath("$.trains[0].arrBasis").value("EXACT"))
                .andExpect(jsonPath("$.trains[0].arrDelayMin").value(4.0))
                .andExpect(jsonPath("$.trains[0].meta.kind").doesNotExist())
                .andExpect(jsonPath("$.trains[0].meta.label").value("서울발 대전행"));
        mvc.perform(get("/api/v1/rail/od/punctuality").param("dep", "S1").param("arr", "S2")
                        .param("from", ref.minusDays(1).toString()).param("to", ref.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.onTimeRate").value(1.0))
                .andExpect(jsonPath("$.depStation").value("서울"));
        // 요일별: 공휴일은 요일과 따로 'H'
        mvc.perform(get("/api/v1/rail/od/punctuality").param("dep", "S1").param("arr", "S2").param("groupBy", "dow")
                        .param("from", ref.minusDays(1).toString()).param("to", ref.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].key").value("H"));
        mvc.perform(get("/api/v1/rail/od/trains").param("dep", "S1").param("arr", "S1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stations").param("q", "대")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("대전"));
        // 역 선택 목록: 가나다순
        mvc.perform(get("/api/v1/stations").param("sort", "name").param("limit", "400")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("대전")).andExpect(jsonPath("$[1].name").value("서울"));
        mvc.perform(get("/api/v1/stations").param("sort", "bogus")).andExpect(status().isBadRequest());
        // 역 코드 형식 제한 (캐시 키 · SQL 매개변수로 쓰임)
        mvc.perform(get("/api/v1/rail/od/trains").param("dep", "S1' OR 1=1").param("arr", "S2")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/rail/od/punctuality").param("dep", "S1").param("arr", "S2").param("groupBy", "x")
                .param("from", ref.toString()).param("to", ref.toString())).andExpect(status().isBadRequest());
    }

    @Test
    void delayBandsCountRunsAtOrBeyondCompensationThresholds() throws Exception {
        // AR-3 · A11 · A12: 배상 기준 시간(20·40·60·90·120분 '이상'). 계획 시각을 모르는 운행은 분자 · 분모 모두에서 빠진다
        LocalDate base = LocalDate.now(KST).minusDays(20);
        double[] delays = {0, 19, 20, 45, 61, 125};
        for (int i = 0; i < delays.length; i++) insertRun(base.plusDays(i), "00300", delays[i], true);
        insertRun(base.plusDays(6), "00301", 200, false);   // 정시성 원본 없음 → 확인 불가(NONE)
        mvc.perform(get("/api/v1/rail/od/punctuality").param("dep", "S1").param("arr", "S2").param("groupBy", "train")
                        .param("from", base.toString()).param("to", base.plusDays(6).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.samples").value(7))
                .andExpect(jsonPath("$.summary.delayBands.verified").value(6))
                .andExpect(jsonPath("$.summary.delayBands.ge20").value(4))
                .andExpect(jsonPath("$.summary.delayBands.ge40").value(3))
                .andExpect(jsonPath("$.summary.delayBands.ge60").value(2))
                .andExpect(jsonPath("$.summary.delayBands.ge90").value(1))
                .andExpect(jsonPath("$.summary.delayBands.ge120").value(1))
                .andExpect(jsonPath("$.items[?(@.key == '00300')].delayBands.ge20").value(hasItem(4)))
                .andExpect(jsonPath("$.items[?(@.key == '00301')].delayBands.verified").value(hasItem(0)))
                .andExpect(jsonPath("$.nationwideExact.delayBands.ge60").value(2))
                .andExpect(jsonPath("$.rules['DB-v1']").exists())
                .andExpect(jsonPath("$.summary.onTimeRate").exists());   // 기존 필드는 그대로 (A13)
    }

    /** S1 10:00 → S2 11:00 계획 열차 한 편. exact 면 정시성 원본(계획 시각)도 넣어 '검증 운행'이 된다 */
    private void insertRun(LocalDate day, String trn, double delayMin, boolean exact) {
        OffsetDateTime dep = day.atTime(10, 0).atZone(KST).toOffsetDateTime();
        OffsetDateTime arr = dep.plusMinutes(60).plusSeconds(Math.round(delayMin * 60));
        jdbc.update("INSERT INTO rail.run_info (run_ymd, trn_no, run_seq, stn_cd, stn_nm, dep_at) VALUES (?, ?, 1, 'S1', '서울', ?)", day, trn, dep);
        jdbc.update("INSERT INTO rail.run_info (run_ymd, trn_no, run_seq, stn_cd, stn_nm, arr_at) VALUES (?, ?, 2, 'S2', '대전', ?)", day, trn, arr);
        if (!exact) return;
        jdbc.update("""
                INSERT INTO rail.train_punctuality (run_ymd, trn_no, dep_stn_cd, arr_stn_cd, plan_dep_at, plan_arr_at, act_dep_at,
                  act_arr_at, dep_delay_min, arr_delay_min, on_time, status, calc_rule, threshold_min)
                VALUES (?, ?, 'S1', 'S2', ?, ?, ?, ?, 0, ?, ?, 'OK', 'P-v1', 5)""",
                day, trn, dep, dep.plusMinutes(60), dep, arr, delayMin, delayMin <= 5);
    }

    @Test
    void stationSearchOrdersExactThenPrefixThenTrains() throws Exception {
        // 역 검색 순서(검색 추천): 이름 정확 일치 → 앞부분 일치 → 최근 7일 정차 편수 → 이름. 캐시로 옮겨도 같아야 한다 (PERF-02)
        LocalDate ref = LocalDate.now(KST).minusDays(7);
        jdbc.execute("INSERT INTO ref.station (stn_cd, stn_nm, lat, lon, source) VALUES ('S3', '서대전', 36.32, 127.40, 'KAKAO'), "
                + "('S4', '대전조차장', 36.35, 127.43, 'KAKAO'), ('S5', '신탄진', 36.45, 127.43, 'KAKAO')");
        for (int i = 0; i < 3; i++) {  // 서대전 3회 > 대전조차장 1회 — 그래도 앞부분 일치가 먼저
            jdbc.update("INSERT INTO rail.run_info (run_ymd, trn_no, run_seq, stn_cd, stn_nm, dep_at) VALUES (?, ?, 1, 'S3', '서대전', now())", ref, "0030" + i);
        }
        jdbc.update("INSERT INTO rail.run_info (run_ymd, trn_no, run_seq, stn_cd, stn_nm, dep_at) VALUES (?, '00400', 1, 'S4', '대전조차장', now())", ref);
        mvc.perform(get("/api/v1/stations").param("q", "대전")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("대전", "대전조차장", "서대전")))  // 신탄진(운행 없음 · 이름 불일치)은 없음
                .andExpect(jsonPath("$[2].trains7d").value(3));
        mvc.perform(get("/api/v1/stations").param("sort", "name").param("limit", "400"))
                .andExpect(jsonPath("$[*].name", contains("대전", "대전조차장", "서대전", "서울")));  // 운행 있는 역만 · 가나다순
    }

    @Test
    void tripBetweenStationsWithoutKakaoKey() throws Exception {
        // 카카오 키가 없으면 자동차 값 없이 기차만 — 결론 대신 근거와 함께 '비교할 수 없습니다'
        mvc.perform(get("/api/v1/trip").param("fromLat", "37.55").param("fromLon", "126.97").param("fromName", "서울역")
                        .param("fromStation", "S1").param("toLat", "36.33").param("toLon", "127.43").param("toName", "대전역")
                        .param("toStation", "S2").param("departIn", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceKm").value(org.hamcrest.Matchers.closeTo(140.0, 10.0)))
                .andExpect(jsonPath("$.rail.journeys[0].legs[0].fromName").value("서울"))
                .andExpect(jsonPath("$.rail.journeys[0].legs[0].toName").value("대전"))
                .andExpect(jsonPath("$.rail.journeys[0].access.mode").value("WALK"))
                .andExpect(jsonPath("$.rail.journeys[0].transfers").value(0))
                .andExpect(jsonPath("$.rail.journeys[0].legs[0].pathOnTrack").value(true))
                .andExpect(jsonPath("$.rail.journeys[0].legs[0].path", hasSize(3)))
                .andExpect(jsonPath("$.rail.journeys[0].legs[0].meta.label").value("서울발 대전행"))
                .andExpect(jsonPath("$.decision.reasons", not(empty())))
                .andExpect(jsonPath("$.env.origin.name").value("서울역"))
                // 출발일이 공휴일 → 경고 · 지하철 시각은 표시하지 않음(TAGO 요일 구분에 공휴일이 없어)
                .andExpect(jsonPath("$.decision.warnings", hasItem(org.hamcrest.Matchers.containsString("공휴일(테스트휴일)"))))
                .andExpect(jsonPath("$.rail.subwayAtDeparture", empty()));
        mvc.perform(get("/api/v1/trip").param("fromLat", "10").param("fromLon", "10").param("fromName", "x")
                        .param("toLat", "36.33").param("toLon", "127.43").param("toName", "y"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tripCacheKeyIncludesStationPinAndName() throws Exception {
        // 같은 좌표라도 역 지정 · 이름이 다르면 다른 판단 카드 — 예전 키는 좌표만 봐서 앞 요청의 결과를 돌려주었다 (BUG-04)
        mvc.perform(get("/api/v1/trip").param("fromLat", "37.55").param("fromLon", "126.97").param("fromName", "서울역")
                        .param("fromStation", "S1").param("toLat", "36.33").param("toLon", "127.43").param("toName", "대전역"))
                .andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS"));
        var plain = get("/api/v1/trip").param("fromLat", "37.55").param("fromLon", "126.97").param("fromName", "서울 시내")
                .param("toLat", "36.33").param("toLon", "127.43").param("toName", "대전역");
        mvc.perform(plain).andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.from.name").value("서울 시내")).andExpect(jsonPath("$.from.stationCode").isEmpty());
        mvc.perform(plain).andExpect(header().string("X-Cache", "HIT"));
    }

    @Test
    void arrivalByDeadlineGivesLatestTrainWithFrequencyAndCachesIt() throws Exception {
        // 씨앗 열차: 오늘 23:58 서울 → 00:55 대전. 기한 내일 01:30 → 늦어도 23:52(역까지 도보 1분 + 승차 여유 5분) 출발.
        // 검증 운행이 1회뿐이라 퍼센트 없이 빈도만, 신뢰 수준 판단도 하지 않는다(15회 미만). 자정 언저리에는 기한 범위가 맞지 않아 건너뛴다
        var now = java.time.LocalDateTime.now(KST);
        org.junit.jupiter.api.Assumptions.assumeTrue(now.getHour() >= 2 && now.getHour() < 23);
        String by = now.toLocalDate().plusDays(1).atTime(1, 30).toString();
        var req = get("/api/v1/trip/arrival").param("fromLat", "37.55").param("fromLon", "126.97").param("fromName", "서울역")
                .param("fromStation", "S1").param("toLat", "36.33").param("toLon", "127.43").param("toName", "대전역")
                .param("toStation", "S2").param("arriveBy", by);
        mvc.perform(req).andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.confidence").value(0.9))
                .andExpect(jsonPath("$.train.journey.legs[0].trnNo").value("00199"))
                .andExpect(jsonPath("$.train.latestDepart").value(org.hamcrest.Matchers.containsString("T23:52")))
                .andExpect(jsonPath("$.train.odds.n").value(1))
                .andExpect(jsonPath("$.train.odds.within").value(1))
                .andExpect(jsonPath("$.train.odds.percent").isEmpty())
                .andExpect(jsonPath("$.train.meetsConfidence").value(false))
                .andExpect(jsonPath("$.train.legRisks[0].kind").value("ARRIVAL"))
                .andExpect(jsonPath("$.car.latestDepart").isEmpty())
                .andExpect(jsonPath("$.car.note").value(org.hamcrest.Matchers.containsString("카카오 키")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("15회 미만")))
                .andExpect(jsonPath("$.assumptions", not(empty())))
                .andExpect(jsonPath("$.pending").value(false));
        mvc.perform(req).andExpect(header().string("X-Cache", "HIT"));
    }

    @Test
    void arrivalRejectsDeadlineOutsideWindowAndUnknownConfidence() throws Exception {
        var base = get("/api/v1/trip/arrival").param("fromLat", "37.55").param("fromLon", "126.97").param("fromName", "x")
                .param("toLat", "36.33").param("toLon", "127.43").param("toName", "y");
        var now = java.time.LocalDateTime.now(KST).withSecond(0).withNano(0);
        for (String bad : new String[]{now.plusMinutes(10).toString(), now.plusHours(25).toString(), "2026-13-01T10:00", "내일"}) {
            mvc.perform(base.param("arriveBy", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(get("/api/v1/trip/arrival").param("fromLat", "37.55").param("fromLon", "126.97").param("fromName", "x")
                        .param("toLat", "36.33").param("toLon", "127.43").param("toName", "y")
                        .param("arriveBy", now.plusHours(3).toString()).param("confidence", "0.85"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void nonFiniteCoordinatesAreRejected() throws Exception {
        // NaN 은 모든 비교가 거짓이라 '범위 밖(<, >)' 조건을 통과했다 → 200 · 0.0km 판단 · 외부 API 호출 (SEC-03)
        for (String bad : new String[]{"NaN", "Infinity", "-Infinity"}) {
            mvc.perform(get("/api/v1/trip").param("fromLat", bad).param("fromLon", "126.97").param("fromName", "x")
                            .param("toLat", "36.33").param("toLon", "127.43").param("toName", "y"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            mvc.perform(get("/api/v1/road/route").param("fromLat", "37.55").param("fromLon", "126.97").param("fromName", "x")
                            .param("toLat", "36.33").param("toLon", bad).param("toName", "y"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void forecastAndBaseline() throws Exception {
        mvc.perform(get("/api/v1/corridors/SEL-DJN/road/forecast").param("dir", "DN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].M0").value(5580))
                .andExpect(jsonPath("$.items[0].persistence").value(7260))
                .andExpect(jsonPath("$.backtest.maeSec.M1").value(nullValue()));
        mvc.perform(get("/api/v1/corridors/SEL-DJN/road/baseline").param("dir", "DN"))
                .andExpect(jsonPath("$.cells", hasSize(288)));
    }

    @Test
    void rateLimitPerClientReturns429WithRetryAfter() throws Exception {
        // 장소 검색 한도 분당 3회(테스트 설정) — 4번째는 429 RATE_LIMITED + Retry-After
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/places/search").param("q", "대전")).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"));
        }
        mvc.perform(get("/api/v1/places/search").param("q", "대전")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        // 다른 클라이언트(프록시가 붙인 다른 주소)는 따로 센다
        mvc.perform(get("/api/v1/places/search").param("q", "대전").header("X-Forwarded-For", "198.51.100.7"))
                .andExpect(status().isOk());
    }

    @Test
    void roadCompletenessLeavesOutSlotsTheSourceNeverHad() throws Exception {
        // M3: 원천에 표본이 없는 슬롯(새벽 등, 전체를 다시 받아도 빔)까지 결측으로 세어 수집이 완벽한 날도 63~71% — 경고가 늘 켜졌다
        OffsetDateTime day = OffsetDateTime.now(KST).withHour(0).withMinute(0).withSecond(0).withNano(0);
        jdbc.execute("TRUNCATE ts.road_corridor_tt");
        jdbc.update("DELETE FROM ops.slot_gap");
        for (int m = 0; m < 60; m += 5) {
            for (String dir : List.of("DN", "UP")) {
                if (dir.equals("DN") && (m == 10 || m == 20)) {
                    jdbc.update("INSERT INTO ops.slot_gap (job_name, series_key, slot_ts, reason) VALUES ('road_travel_time', ?, ?, 'NO_SAMPLES')",
                            "SEL-DJN:DN", day.plusMinutes(m));
                    continue;
                }
                jdbc.update("INSERT INTO ts.road_corridor_tt VALUES (?, 'SEL-DJN', ?, 7260, 10, 10, 'OK', now())", day.plusMinutes(m), dir);
            }
        }
        try {
            mvc.perform(get("/api/v1/ops/collect-status")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobs[?(@.job == 'road_travel_time')].completeness24h").value(hasItem(1.0)))
                    .andExpect(jsonPath("$.jobs[?(@.job == 'road_travel_time')].gaps24h").value(hasItem(0)))
                    .andExpect(jsonPath("$.jobs[?(@.job == 'road_travel_time')].noSamples24h").value(hasItem(2)));
        } finally {
            jdbc.update("DELETE FROM ops.slot_gap");
        }
    }

    @Test
    void routeIncidentsSortNewestFirstIncludingCorridorMatchedOnes() {
        // RVW-08: 좌표 없는 길 매칭 안내를 정렬 뒤에 붙여, 더 최근 것이어도 '최근 20건' 자르기에서 먼저 빠졌다
        jdbc.update("DELETE FROM ts.road_incident");
        jdbc.update("""
                INSERT INTO ts.road_incident (msg_hash, sent_at, type_code, type_name, route_name, content, lat, lon, corridor_ids, source)
                VALUES (repeat('a', 64), now() - interval '30 minutes', '1', '사고', '경부선', '좌표 있음 · 오래됨', 37.50, 126.97, '{}', 'EX'),
                       (repeat('b', 64), now(), '1', '사고', '경부선', '좌표 없음 · 최신', NULL, NULL, '{SEL-DJN}', 'EX')""");
        try {
            var got = env.routeIncidents(List.of(new double[]{37.55, 126.97}, new double[]{37.45, 126.97}), "SEL-DJN");
            assertThat(got).extracting(EnvDtos.Incident::content).containsExactly("좌표 없음 · 최신", "좌표 있음 · 오래됨");
        } finally {
            jdbc.update("DELETE FROM ts.road_incident");
        }
    }

    @Test
    void storedForecastForNowComesFromTheLatestIssueThatHasThatHour() {
        // RVW-02: 단기예보 새 발표는 발표 +1시간부터 값이 있다(14시 발표 → 15시부터). 가장 최근 발표에서만 찾아
        // 발표 직후 한 시간(하루 약 6시간)은 '지금 출발' 날씨가 비었다 → 그 시각을 가진 가장 최근 발표에서 읽는다
        OffsetDateTime day = OffsetDateTime.now(KST).withHour(0).withMinute(0).withSecond(0).withNano(0);
        jdbc.update("DELETE FROM env.weather_fcst WHERE nx = 7 AND ny = 7");
        for (int h = 12; h <= 17; h++) insertFcst(day.withHour(11), day.withHour(h), "TMP", "1" + h);   // 11시 발표: 12~17시
        for (int h = 15; h <= 20; h++) insertFcst(day.withHour(14), day.withHour(h), "TMP", "2" + h);   // 14시 발표: 15시~
        assertThat(env.at(7, 7, day.withHour(14).withMinute(30))).get().extracting(EnvDtos.WeatherHour::tmp).isEqualTo(114);
        assertThat(env.at(7, 7, day.withHour(16).withMinute(10))).get().extracting(EnvDtos.WeatherHour::tmp).isEqualTo(216);  // 새 발표가 있으면 새 발표
        jdbc.update("DELETE FROM env.weather_fcst WHERE nx = 7 AND ny = 7");
    }

    private void insertFcst(OffsetDateTime base, OffsetDateTime at, String category, String value) {
        jdbc.update("INSERT INTO env.weather_fcst (base_at, fcst_at, nx, ny, category, value) VALUES (?, ?, 7, 7, ?, ?)",
                base, at, category, value);
    }

    @Test
    void apiDocsAreOffByDefault() throws Exception {
        // 공개 배포 체크리스트: Swagger UI · OpenAPI 는 기본으로 끈다 — 로컬에서만 SWAGGER_ENABLED=true (ADR-028)
        for (String path : List.of("/v3/api-docs", "/docs", "/swagger-ui/index.html")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void everyResponseCarriesAntiFramingHeaders() throws Exception {
        // WEB-10: API 문서(Swagger UI)를 웹이 외부 rewrite 로 넘겨 보안 헤더가 하나도 없었다 — API 가 직접 붙인다
        for (String path : List.of("/api/v1/corridors", "/v3/api-docs")) {
            mvc.perform(get(path)).andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
    }

    @Test
    void unknownAdminPathsAreRateLimitedToo() throws Exception {
        // 리뷰: 컨트롤러가 없는 관리 경로는 한도 밖이라 토큰을 무제한 대입할 수 있었다(틀리면 401 · 맞으면 404)
        for (String path : List.of("/api/v1/admin/x", "/api/v1/admin/%78")) {
            mvc.perform(post(java.net.URI.create(path)).header("X-Admin-Token", "wrong").header("X-Forwarded-For", "192.0.2.45"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("X-RateLimit-Limit"));
        }
    }

    @Test
    void rateLimitCountsEncodedAndMatrixParameterPathsToo() throws Exception {
        // RVW-04: 버킷을 디코딩 전 URI 로 골라 같은 핸들러로 가는 /places/%73earch · /places;x=1/search 는 한도 밖이었다
        for (String path : List.of("/api/v1/places/%73earch", "/api/v1/places;x=1/search")) {
            mvc.perform(get(java.net.URI.create(path + "?q=%EB%8C%80%EC%A0%84")).header("X-Forwarded-For", "192.0.2.44"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "3"));
        }
    }

    @Test
    void qa01_forecastHorizonsWithEmptyItemsIsA400NotA500() throws Exception {
        // QA-01: horizons=",,," 처럼 빈 항목이 있으면 목록에 null 이 들어와 검증에서 NPE → 500 INTERNAL_ERROR
        for (String h : List.of(",,,", "60,,120", ",")) {
            mvc.perform(get("/api/v1/corridors/SEL-DJN/road/forecast").param("dir", "DN").param("horizons", h))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void qa05_aBackfillForARangeAlreadyQueuedIsRefusedNotQueuedAgain() throws Exception {
        // QA-05: 같은 기간 백필을 연달아(또는 동시에) 요청하면 모두 202 로 대기열에 쌓였다 — 실행 단계에서 하나만 돌고 나머지는
        // FAILED 로 남거나, 순서대로 돌며 같은 코레일 호출을 되풀이할 수 있다. 겹치는 기간이 대기 · 실행 중이면 409
        LocalDate y = LocalDate.now(KST).minusDays(1);
        String body = "{\"provider\":\"KORAIL\",\"job\":\"rail_daily\",\"from\":\"%s\",\"to\":\"%s\"}";
        mvc.perform(post("/api/v1/admin/backfill").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(y.minusDays(5), y.minusDays(3))))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/admin/backfill").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(y.minusDays(4), y)))      // 겹침
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_RUNNING"));
        mvc.perform(post("/api/v1/admin/backfill").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(y.minusDays(2), y)))      // 겹치지 않음
                .andExpect(status().isAccepted());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.backfill", Integer.class)).isEqualTo(2);
    }

    @Test
    void qa05_concurrentIdenticalBackfillsQueueOnlyOne() throws Exception {
        // QA-05: QA 스택 실측 — 같은 기간 백필 5건을 동시에 보내면 5건 모두 202 · 대기열 5건
        LocalDate y = LocalDate.now(KST).minusDays(1);
        String body = "{\"provider\":\"KORAIL\",\"job\":\"rail_daily\",\"from\":\"%s\",\"to\":\"%s\"}".formatted(y.minusDays(8), y.minusDays(7));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(5)) {
            var codes = pool.invokeAll(java.util.Collections.nCopies(5, (java.util.concurrent.Callable<Integer>) () ->
                    mvc.perform(post("/api/v1/admin/backfill").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus()));
            var list = new java.util.ArrayList<Integer>();
            for (var c : codes) list.add(c.get());
            org.assertj.core.api.Assertions.assertThat(list).containsOnlyOnce(202).containsOnly(202, 409);
        }
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.backfill", Integer.class)).isEqualTo(1);
    }
}
