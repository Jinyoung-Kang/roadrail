package com.roadrail.ops.app;

import com.roadrail.shared.Times;
import com.roadrail.shared.AppProperties;
import com.roadrail.ops.model.OpsDtos.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.roadrail.ops.data.OpsRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 수집 상태 (FR-701, NFR-01/02/09). 완전성 95% 미만이면 warn. */
@Service
public class OpsService {
    public static final List<String> PROVIDERS = List.of("EX", "KORAIL", "KMA", "AIRKOREA", "KAKAO", "KAKAO_LOCAL", "TAGO", "TAGO_TRAIN", "OSM", "KASI", "UTIC");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");  // BASIC_ISO_DATE 는 오프셋(+0900)까지 붙인다
    private final OpsRepository repo;
    private final StringRedisTemplate redis;
    private final AppProperties props;

    public OpsService(OpsRepository repo, StringRedisTemplate redis, AppProperties props) {
        this.repo = repo;
        this.redis = redis;
        this.props = props;
    }

    public Status status() {
        OffsetDateTime now = Times.now();
        Map<String, Double> completeness = completeness();
        // 원천에 표본이 없는 슬롯(NO_SAMPLES — 전체를 다시 받아도 빔)은 수집 결측이 아니라 따로 센다(M3)
        var open = repo.openGaps24h();
        Map<String, Integer> gaps = open.gaps(), noSamples = open.noSamples();
        List<String> names = repo.jobNames();
        Set<String> running = runningJobs(names);  // 잠금 확인을 작업마다 1번씩 → MGET 1번
        List<Job> jobs = repo.jobs().stream().map(r -> {
            Double c = completeness.get(r.job());
            String last = r.lastStatus();
            boolean warn = (c != null && c < 0.95) || "FAILED".equals(last) || "SKIPPED_QUOTA".equals(last);
            return new Job(r.job(), r.provider(), r.cron(), r.description(), r.enabled(), last, r.lastRunAt(), r.lastDurationMs(),
                    r.lastCalls(), r.lastRows(), r.lastMessage(), c,
                    gaps.getOrDefault(r.job(), c == null ? null : 0), noSamples.getOrDefault(r.job(), c == null ? null : 0),
                    running.contains(r.job()), warn);
        }).toList();
        List<Run> runs = repo.recentRuns();
        List<ApiError> errors = repo.recentApiErrors();
        List<Failure> failures = failures();
        List<Backfill> backfills = repo.recentBackfills();
        List<Lag> lag = repo.publishLag();  // 공개 지연: 원본 행이 처음 저장된 시각 − 슬롯 시각
        String hb = safe(() -> redis.opsForValue().get("rr:collector:heartbeat"));
        return new Status(now, hb != null, hb == null ? null : OffsetDateTime.parse(hb), jobs, quotas(), runs, errors,
                failures, backfills, lag, volumes());
    }

    private record Volumes(Map<String, Object> values, long measuredAt) {}

    private volatile Volumes volumes;

    /**
     * 저장 규모 — 전체 행 수를 정확히 센다(추정치를 내지 않음). 운행정보는 영구 보관이라 행 수에 비례해 느려지므로(84.5만 행 count 63ms)
     * 30초마다 갱신되는 화면이 부를 때마다 세지 않고 5분에 한 번만, 센 시각(measured_at)과 함께 낸다 (PERF-01).
     */
    Map<String, Object> volumes() {
        Volumes v = volumes;
        if (v != null && System.currentTimeMillis() - v.measuredAt() < 300_000) return v.values();
        Map<String, Object> vol = repo.volumes();
        vol.put("measured_at", Times.now().format(Times.HM));
        volumes = new Volumes(vol, System.currentTimeMillis());
        return vol;
    }

    private Set<String> runningJobs(List<String> names) {
        List<String> locks = safe(() -> redis.opsForValue().multiGet(names.stream().map(j -> "rr:lock:" + j).toList()));
        Set<String> out = new HashSet<>();
        for (int i = 0; locks != null && i < names.size(); i++) if (locks.get(i) != null) out.add(names.get(i));
        return out;
    }

    /**
     * 최근 24시간 오류 실행 (작업별 최신 3건, 최대 20건). 전체 내용은 수집기가 실행 끝에 ops.job_run.detail 에 남긴다.
     * detail 이 없는 실행(V8 이전)은 메시지와 그 시간대의 실패한 외부 호출로 만든다. 해결 안 된 것부터.
     */
    List<Failure> failures() {
        return repo.failures();
    }

    /**
     * 작업별 24시간 완전성 = 저장된 슬롯 / 기대 슬롯.
     * 기대 슬롯의 시작은 max(24시간 전, 그 작업의 첫 수집) — 수집 시작 전 시간은 분모에 넣지 않는다.
     * 도로는 공개 워터마크(최신 슬롯) 기준 창을 쓰고, API 가 지난 날짜를 주지 않으므로 첫 수집일 0시부터 기대한다.
     */
    Map<String, Double> completeness() {
        Map<String, Double> m = new HashMap<>();
        repo.roadCompleteness().ifPresent(v -> m.put("road_travel_time", round3(Math.min(v, 1))));
        put(m, "road_volume_all", repo.windowCompleteness("ts.road_volume", "slot_ts", 15, "count(DISTINCT slot_ts)"));
        put(m, "weather_vilage", repo.windowCompleteness("env.weather_fcst", "base_at", 180, "count(DISTINCT base_at)"));
        put(m, "air_quality_sido", repo.windowCompleteness("env.air_quality", "data_time", 60, "count(DISTINCT date_trunc('hour', data_time))"));
        put(m, "kakao_eta", repo.windowCompleteness("ana.kakao_eta", "requested_at", 60, "count(DISTINCT date_trunc('hour', requested_at))"));
        put(m, "rail_daily", repo.railDailyCompleteness());
        return m;
    }

    private static void put(Map<String, Double> m, String job, Optional<Double> value) {
        Double v = value.orElse(null);
        if (v != null) m.put(job, round3(v));
    }

    private static Double round3(Double v) { return v == null ? null : Math.round(v * 1000) / 1000.0; }

    public List<Quota> quotas() {
        String day = Times.now().format(YMD);
        List<String> keys = new ArrayList<>();
        for (String p : PROVIDERS) {
            keys.add("quota:" + p + ":" + day);
            keys.add("quota:used:" + p + ":" + day);
        }
        List<String> v = safe(() -> redis.opsForValue().multiGet(keys));  // 공급자당 GET 2번 → MGET 1번
        List<Quota> out = new ArrayList<>();
        for (int i = 0; i < PROVIDERS.size(); i++) {
            String p = PROVIDERS.get(i);
            int limit = props.quotaOf(p);
            int total = parse(v == null ? null : v.get(2 * i)), used = parse(v == null ? null : v.get(2 * i + 1));
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
