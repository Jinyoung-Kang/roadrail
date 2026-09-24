package com.roadrail.service;

import com.roadrail.config.AppProperties;
import com.roadrail.external.QuotaGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 카카오모빌리티 미래 운행 정보 길찾기 (FR-504). 출발 예정 시각 기준 자동차 소요시간과 경로 좌표.
 * 키가 없거나 실패하면 빈 값 (판단은 다른 근거로). 호출은 collector 와 같은 KAKAO 예산을 쓴다.
 */
@Component
public class KakaoMobilityClient {
    private static final Logger log = LoggerFactory.getLogger(KakaoMobilityClient.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Duration WAIT = Duration.ofMillis(600);
    private static final int MAX_PATH_POINTS = 400;
    private final RestClient http;
    private final AppProperties props;
    private final JsonCache cache;
    private final QuotaGuard quota;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<Eta>> inflight = new ConcurrentHashMap<>();

    /** path = [[lat, lon], …] (최대 400점, 경로가 필요할 때만) */
    public record Eta(int durationSec, int distanceM, String departAt, List<double[]> path) {}

    public KakaoMobilityClient(RestClient.Builder builder, AppProperties props, JsonCache cache, QuotaGuard quota) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.http = builder.clone().baseUrl(props.kakaoMobilityBaseUrl()).requestFactory(factory).build();
        this.props = props;
        this.cache = cache;
        this.quota = quota;
    }

    public Optional<Eta> futureEta(double oLat, double oLon, double dLat, double dLon, OffsetDateTime departAt) {
        return futureEta(oLat, oLon, dLat, dLon, departAt, false);
    }

    /**
     * 카카오 응답은 약 1초 — 판단 카드 미적중 p95 &lt; 1s (NFR-03) 를 지키려고 최대 WAIT 만 기다린다 (ADR-010).
     * 늦으면 빈 값으로 응답하고, 호출은 가상 스레드에서 끝까지 진행해 캐시를 채운다 (다음 요청부터 적중).
     */
    public Optional<Eta> futureEta(double oLat, double oLon, double dLat, double dLon, OffsetDateTime departAt, boolean withPath) {
        if (props.kakaoRestApiKey() == null || props.kakaoRestApiKey().isBlank()) return Optional.empty();
        // 10분 단위로 맞춰 캐시 (같은 시각 반복 호출 방지, 쿼터 보호)
        OffsetDateTime t = departAt.withMinute(departAt.getMinute() - departAt.getMinute() % 10).withSecond(0).withNano(0);
        if (t.isBefore(OffsetDateTime.now().plusMinutes(1))) t = t.plusMinutes(10);
        String dep = t.format(FMT);
        String key = String.format(Locale.ROOT, "kakao:eta%s:%.4f,%.4f:%.4f,%.4f:%s", withPath ? "p" : "", oLat, oLon, dLat, dLon, dep);
        Eta hit = cache.peek(key, Eta.class);
        if (hit != null) return Optional.of(hit);
        CompletableFuture<Eta> f = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            Eta e = call(oLat, oLon, dLat, dLon, dep, withPath);
            if (e != null) cache.put(k, e, Duration.ofMinutes(20));
            return e;
        }, exec).whenComplete((e, ex) -> inflight.remove(k)));
        try {
            return Optional.ofNullable(f.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        }
    }

    /** 같은 요청이 아직 진행 중인지 (화면이 잠시 뒤 다시 부르도록 알려 주기 위함) */
    public boolean pending(double oLat, double oLon, double dLat, double dLon, OffsetDateTime departAt, boolean withPath) {
        OffsetDateTime t = departAt.withMinute(departAt.getMinute() - departAt.getMinute() % 10).withSecond(0).withNano(0);
        if (t.isBefore(OffsetDateTime.now().plusMinutes(1))) t = t.plusMinutes(10);
        return inflight.containsKey(String.format(Locale.ROOT, "kakao:eta%s:%.4f,%.4f:%.4f,%.4f:%s", withPath ? "p" : "",
                oLat, oLon, dLat, dLon, t.format(FMT)));
    }

    private Eta call(double oLat, double oLon, double dLat, double dLon, String dep, boolean withPath) {
        if (!quota.take("KAKAO")) return null;
        try {
            JsonNode body = http.get().uri(u -> u.path("/v1/future/directions")
                            .queryParam("origin", oLon + "," + oLat).queryParam("destination", dLon + "," + dLat)
                            .queryParam("departure_time", dep).queryParam("summary", withPath ? "false" : "true").build())
                    .header("Authorization", "KakaoAK " + props.kakaoRestApiKey())
                    .retrieve().body(JsonNode.class);
            JsonNode r = body == null ? null : body.path("routes").path(0);
            if (r == null || r.path("result_code").asInt(-1) != 0) return null;
            JsonNode s = r.path("summary");
            return new Eta(s.path("duration").asInt(), s.path("distance").asInt(), dep, withPath ? path(r) : null);
        } catch (RuntimeException e) {
            log.warn("카카오 미래 운행 정보 조회 실패: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    // ------------------------------------------------------------------ 다중 목적지 · 출발지 (역까지 · 역에서 실제 경로)

    /** key → (소요 초, 거리 m). 반경 10km 안의 목적지만 (카카오 제한). 실패하면 빈 맵. */
    public record Leg(int durationSec, int distanceM) {}

    public Map<String, Leg> manyDestinations(double oLat, double oLon, Map<String, double[]> dests) {
        return many("/v1/destinations/directions", "origin", "destinations", oLat, oLon, dests);
    }

    public Map<String, Leg> manyOrigins(Map<String, double[]> origins, double dLat, double dLon) {
        return many("/v1/origins/directions", "destination", "origins", dLat, dLon, origins);
    }

    private Map<String, Leg> many(String path, String oneField, String manyField, double lat, double lon, Map<String, double[]> pts) {
        if (pts.isEmpty() || props.kakaoRestApiKey() == null || props.kakaoRestApiKey().isBlank()) return Map.of();
        String key = String.format(Locale.ROOT, "kakao:many:%s:%.4f,%.4f:%s", manyField, lat, lon,
                String.join(",", new java.util.TreeSet<>(pts.keySet())));
        LegMap hit = cache.peek(key, LegMap.class);
        if (hit != null) return hit.legs();
        if (!quota.take("KAKAO")) return Map.of();
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            pts.forEach((k, v) -> list.add(Map.of("x", String.valueOf(v[1]), "y", String.valueOf(v[0]), "key", k)));
            Map<String, Object> body = Map.of(oneField, Map.of("x", String.valueOf(lon), "y", String.valueOf(lat)),
                    manyField, list, "radius", 10000);
            JsonNode res = http.post().uri(path).header("Authorization", "KakaoAK " + props.kakaoRestApiKey())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            Map<String, Leg> out = new java.util.HashMap<>();
            if (res != null) for (JsonNode r : res.path("routes")) {
                if (r.path("result_code").asInt(-1) == 0) {
                    out.put(r.path("key").asString(), new Leg(r.path("summary").path("duration").asInt(), r.path("summary").path("distance").asInt()));
                }
            }
            cache.put(key, new LegMap(out), Duration.ofMinutes(20));
            return out;
        } catch (RuntimeException e) {
            log.warn("카카오 다중 길찾기 실패: {}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    public record LegMap(Map<String, Leg> legs) {}

    // ------------------------------------------------------------------ 경로 상세 (도로 분석)

    public record Road(String name, int distanceM, int durationSec, int trafficState) {}

    public record Route(int durationSec, int distanceM, Integer tollFare, Integer taxiFare, String departAt,
                        List<double[]> path, List<Road> roads) {}

    /** 출발 시각 기준 경로 상세. avoid = null | "motorway" (고속도로 회피 → 국도·일반도로 위주). 결과 20분 캐시. */
    public Route route(double oLat, double oLon, double dLat, double dLon, OffsetDateTime departAt, String avoid, boolean detail) {
        if (props.kakaoRestApiKey() == null || props.kakaoRestApiKey().isBlank()) return null;
        OffsetDateTime t = departAt.withMinute(departAt.getMinute() - departAt.getMinute() % 10).withSecond(0).withNano(0);
        if (t.isBefore(OffsetDateTime.now().plusMinutes(1))) t = t.plusMinutes(10);
        String dep = t.format(FMT);
        String key = String.format(Locale.ROOT, "kakao:route:%s:%s:%.4f,%.4f:%.4f,%.4f:%s", avoid, detail, oLat, oLon, dLat, dLon, dep);
        Route hit = cache.peek(key, Route.class);
        if (hit != null) return hit;
        if (!quota.take("KAKAO")) return null;
        try {
            JsonNode body = http.get().uri(u -> {
                        var b = u.path("/v1/future/directions").queryParam("origin", oLon + "," + oLat)
                                .queryParam("destination", dLon + "," + dLat).queryParam("departure_time", dep)
                                .queryParam("summary", detail ? "false" : "true");
                        if (avoid != null) b = b.queryParam("avoid", avoid);
                        return b.build();
                    }).header("Authorization", "KakaoAK " + props.kakaoRestApiKey()).retrieve().body(JsonNode.class);
            JsonNode r = body == null ? null : body.path("routes").path(0);
            if (r == null || r.path("result_code").asInt(-1) != 0) return null;
            JsonNode s = r.path("summary");
            List<Road> roads = new ArrayList<>();
            if (detail) for (JsonNode sec : r.path("sections")) for (JsonNode road : sec.path("roads")) {
                roads.add(new Road(road.path("name").asString(""), road.path("distance").asInt(), road.path("duration").asInt(),
                        road.path("traffic_state").asInt(0)));
            }
            Route out = new Route(s.path("duration").asInt(), s.path("distance").asInt(),
                    s.path("fare").path("toll").isMissingNode() ? null : s.path("fare").path("toll").asInt(),
                    s.path("fare").path("taxi").isMissingNode() ? null : s.path("fare").path("taxi").asInt(), dep,
                    detail ? path(r) : List.of(), roads);
            cache.put(key, out, Duration.ofMinutes(20));
            return out;
        } catch (RuntimeException e) {
            log.warn("카카오 경로 조회 실패: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** vertexes = [x1, y1, x2, y2, …] (경도, 위도) → [[lat, lon]] 을 최대 400점으로 균등 추출 */
    static List<double[]> path(JsonNode route) {
        List<double[]> all = new ArrayList<>();
        for (JsonNode sec : route.path("sections")) {
            for (JsonNode road : sec.path("roads")) {
                JsonNode v = road.path("vertexes");
                for (int i = 0; i + 1 < v.size(); i += 2) all.add(new double[]{v.get(i + 1).asDouble(), v.get(i).asDouble()});
            }
        }
        if (all.size() <= MAX_PATH_POINTS) return all;
        List<double[]> out = new ArrayList<>(MAX_PATH_POINTS);
        double step = (all.size() - 1) / (double) (MAX_PATH_POINTS - 1);
        for (int i = 0; i < MAX_PATH_POINTS; i++) out.add(all.get((int) Math.round(i * step)));
        return out;
    }
}
