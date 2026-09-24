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
                .andExpect(jsonPath("$.failures[0].job").value("road_travel_time"))
                .andExpect(jsonPath("$.failures[0].detail").value(org.hamcrest.Matchers.containsString("[스택 트레이스]")))
                .andExpect(jsonPath("$.collectorAlive").value(false));
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
}
