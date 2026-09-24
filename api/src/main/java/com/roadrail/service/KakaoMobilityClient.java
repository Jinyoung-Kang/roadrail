package com.roadrail.service;

import com.roadrail.config.AppProperties;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 카카오모빌리티 미래 운행 정보 길찾기 (FR-504, 선택). 출발 예정 시각 기준 자동차 소요시간을
 * 자체 예측과 나란히 표시한다. 키가 없거나 실패하면 비어 있는 값으로 대체 (판단은 자체 예측으로).
 */
@Component
public class KakaoMobilityClient {
    private static final Logger log = LoggerFactory.getLogger(KakaoMobilityClient.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private final RestClient http;
    private final AppProperties props;
    private final JsonCache cache;
    private static final Duration WAIT = Duration.ofMillis(600);
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<Eta>> inflight = new ConcurrentHashMap<>();

    public record Eta(int durationSec, int distanceM, String departAt) {}

    public KakaoMobilityClient(RestClient.Builder builder, AppProperties props, JsonCache cache) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.http = builder.baseUrl(props.kakaoMobilityBaseUrl()).requestFactory(factory).build();
        this.props = props;
        this.cache = cache;
    }

    public Optional<Eta> futureEta(double oLat, double oLon, double dLat, double dLon, OffsetDateTime departAt) {
        if (props.kakaoRestApiKey() == null || props.kakaoRestApiKey().isBlank()) return Optional.empty();
        // 10분 단위로 맞춰 캐시 (같은 시각 반복 호출 방지, 쿼터 보호)
        OffsetDateTime t = departAt.withMinute(departAt.getMinute() - departAt.getMinute() % 10).withSecond(0).withNano(0);
        if (t.isBefore(OffsetDateTime.now().plusMinutes(1))) t = t.plusMinutes(10);
        String dep = t.format(FMT);
        String key = String.format("kakao:eta:%.4f,%.4f:%.4f,%.4f:%s", oLat, oLon, dLat, dLon, dep);
        Eta hit = cache.peek(key, Eta.class);
        if (hit != null) return Optional.of(hit);
        // 카카오 응답은 약 1초 — 판단 카드 미적중 p95 < 1s (NFR-03) 를 지키려고 최대 WAIT 만 기다린다.
        // 늦으면 카카오 값 없이 응답하고, 호출은 가상 스레드에서 끝까지 진행해 캐시를 채운다 (다음 요청부터 적중).
        CompletableFuture<Eta> f = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            Eta e = call(oLat, oLon, dLat, dLon, dep);
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

    private Eta call(double oLat, double oLon, double dLat, double dLon, String dep) {
        try {
            JsonNode body = http.get().uri(u -> u.path("/v1/future/directions")
                            .queryParam("origin", oLon + "," + oLat).queryParam("destination", dLon + "," + dLat)
                            .queryParam("departure_time", dep).queryParam("summary", "true").build())
                    .header("Authorization", "KakaoAK " + props.kakaoRestApiKey())
                    .retrieve().body(JsonNode.class);
            JsonNode r = body == null ? null : body.path("routes").path(0);
            if (r == null || r.path("result_code").asInt(-1) != 0) return null;
            JsonNode s = r.path("summary");
            return new Eta(s.path("duration").asInt(), s.path("distance").asInt(), dep);
        } catch (RuntimeException e) {
            log.warn("카카오 미래 운행 정보 조회 실패: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
