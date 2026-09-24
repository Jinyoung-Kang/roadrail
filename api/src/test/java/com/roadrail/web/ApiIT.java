package com.roadrail.web;

import com.roadrail.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 시드 DB 로 API 계약 확인 (10장 API·E2E) — 판단 카드 · 오류 규약 · 관리 API 403/409/429 · 캐시. */
@AutoConfigureMockMvc
class ApiIT extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    @Autowired
    MockMvc mvc;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE ref.corridor, ref.toll_unit, ref.station CASCADE");
        jdbc.execute("TRUNCATE ts.road_corridor_tt, ana.road_baseline, rail.corridor_trip, rail.train_punctuality, ops.backfill");
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
                """);
        // 최근 도로 관측 (1시간 전 슬롯) + 전체 요일 기준선
        OffsetDateTime slot = OffsetDateTime.now(KST).withSecond(0).withNano(0).minusHours(1);
        slot = slot.withMinute(slot.getMinute() - slot.getMinute() % 5);
        jdbc.update("INSERT INTO ts.road_corridor_tt VALUES (?, 'SEL-DJN', 'DN', 7260, 10, 10, 'OK', now())", slot);
        jdbc.execute("""
                INSERT INTO ana.road_baseline (corridor_id, direction, dow, slot_idx, p50_sec, p90_sec, n, window_from, window_to)
                SELECT 'SEL-DJN', 'DN', 0, s, 5580, 6400, 20, current_date - 56, current_date FROM generate_series(0, 287) s""");
        // 같은 요일 지난주 열차 2편 (출발 23:58 은 대부분 시각 이후로 잡히도록)
        LocalDate ref = LocalDate.now(KST).minusDays(7);
        jdbc.update("""
                INSERT INTO rail.corridor_trip (run_ymd, corridor_id, direction, trn_no, act_dep_at, act_arr_at, est_plan_dep_at,
                  est_plan_arr_at, dep_delay_min, arr_delay_min, dep_basis, arr_basis, ride_min, on_time, calc_rule)
                VALUES (?, 'SEL-DJN', 'DN', '00199', ?, ?, ?, ?, 1, 3, 'EXACT', 'EST', 60, true, 'P-i1')""",
                ref, ref.atTime(23, 59).atZone(KST).toOffsetDateTime(), ref.atTime(23, 59).atZone(KST).toOffsetDateTime().plusMinutes(60),
                ref.atTime(23, 58).atZone(KST).toOffsetDateTime(), ref.atTime(23, 58).atZone(KST).toOffsetDateTime().plusMinutes(57));
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
        mvc.perform(get("/api/v1/ops/collect-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[?(@.job == 'road_travel_time')].cron").value(hasItem("*/10 * * * *")))
                .andExpect(jsonPath("$.quota[?(@.provider == 'AIRKOREA')].limit").value(hasItem(450)))
                .andExpect(jsonPath("$.collectorAlive").value(false));
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db").value("UP"))
                .andExpect(jsonPath("$.components.collector").value("DOWN"))
                .andExpect(jsonPath("$.status").value("DEGRADED"));
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
}
