package com.roadrail.external;

import com.roadrail.config.AppProperties;
import com.roadrail.service.JsonCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 카카오 로컬: 주소(행정구역) 검색 · 키워드(장소) 검색 · 좌표 → 행정구역. 결과는 Redis 에 하루~30일 캐시. */
@Component
public class KakaoLocalClient {
    private static final Logger log = LoggerFactory.getLogger(KakaoLocalClient.class);
    private static final String BASE = "https://dapi.kakao.com/v2/local";
    private final RestClient http;
    private final AppProperties props;
    private final JsonCache cache;
    private final QuotaGuard quota;

    public record Hit(String name, String address, double lat, double lon, String kind, String region1, String region2) {}

    public record Region(String region1, String region2) {}

    public record Hits(List<Hit> items) {}

    public KakaoLocalClient(RestClient.Builder builder, AppProperties props, JsonCache cache, QuotaGuard quota) {
        this.http = Http.client(builder, Duration.ofSeconds(3));
        this.props = props;
        this.cache = cache;
        this.quota = quota;
    }

    boolean enabled() { return props.kakaoRestApiKey() != null && !props.kakaoRestApiKey().isBlank(); }

    /** 행정구역(시·군·구·읍면동) 주소 검색 */
    public List<Hit> regions(String q) {
        if (!enabled()) return List.of();
        var h = cache.get("kakao:addr:" + q.toLowerCase(Locale.ROOT), Duration.ofDays(1), Hits.class, () -> {
            JsonNode body = call("/search/address.json", Map.of("query", q, "size", 5));
            List<Hit> out = new ArrayList<>();
            if (body != null) for (JsonNode d : body.path("documents")) {
                JsonNode a = d.path("address");
                out.add(new Hit(Http.text(d, "address_name"), Http.text(d, "address_name"), d.path("y").asDouble(),
                        d.path("x").asDouble(), "REGION".equals(Http.text(d, "address_type")) ? "REGION" : "ADDRESS",
                        Http.text(a, "region_1depth_name"), Http.text(a, "region_2depth_name")));
            }
            return body == null ? null : new Hits(out);
        });
        return h.value() == null ? List.of() : h.value().items();
    }

    /** 장소 키워드 검색 (역 · 건물 · 명소 …) */
    public List<Hit> places(String q) {
        if (!enabled()) return List.of();
        var h = cache.get("kakao:kw:" + q.toLowerCase(Locale.ROOT), Duration.ofDays(1), Hits.class, () -> {
            JsonNode body = call("/search/keyword.json", Map.of("query", q, "size", 8));
            List<Hit> out = new ArrayList<>();
            if (body != null) for (JsonNode d : body.path("documents")) {
                String cat = Http.text(d, "category_name");
                String addr = Http.text(d, "road_address_name");
                if (addr == null || addr.isBlank()) addr = Http.text(d, "address_name");
                String[] parts = (Http.text(d, "address_name") == null ? "" : Http.text(d, "address_name")).split(" ");
                out.add(new Hit(Http.text(d, "place_name"), addr, d.path("y").asDouble(), d.path("x").asDouble(),
                        cat != null && cat.contains("기차역") ? "STATION" : "PLACE",
                        parts.length > 0 ? parts[0] : null, parts.length > 1 ? parts[1] : null));
            }
            return body == null ? null : new Hits(out);
        });
        return h.value() == null ? List.of() : h.value().items();
    }

    /** 좌표 → 행정구역 (에어코리아 시도 결정용). 소수 셋째 자리로 묶어 30일 캐시. */
    public Region region(double lat, double lon) {
        if (!enabled()) return null;
        String key = String.format(Locale.ROOT, "kakao:c2r:%.3f,%.3f", lat, lon);
        return cache.get(key, Duration.ofDays(30), Region.class, () -> {
            JsonNode body = call("/geo/coord2regioncode.json", Map.of("x", lon, "y", lat));
            if (body == null) return null;
            for (JsonNode d : body.path("documents")) {
                if ("H".equals(Http.text(d, "region_type")) || "B".equals(Http.text(d, "region_type"))) {
                    return new Region(Http.text(d, "region_1depth_name"), Http.text(d, "region_2depth_name"));
                }
            }
            return null;
        }).value();
    }

    private JsonNode call(String path, Map<String, ?> params) {
        if (!quota.take("KAKAO_LOCAL")) return null;
        try {
            return http.get().uri(Http.uri(BASE + path, params))
                    .header("Authorization", "KakaoAK " + props.kakaoRestApiKey()).retrieve().body(JsonNode.class);
        } catch (RuntimeException e) {
            log.warn("카카오 로컬 {} 실패: {}", path, e.getClass().getSimpleName());
            return null;
        }
    }
}
