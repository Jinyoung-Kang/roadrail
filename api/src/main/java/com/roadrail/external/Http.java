package com.roadrail.external;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** 외부 API 호출 공통: 짧은 타임아웃 · 쿼리 값은 직접 URL 인코딩 (인증키의 '+' '/' '=' 가 깨지지 않게). */
final class Http {
    private Http() {}

    static RestClient client(RestClient.Builder builder, Duration read) {
        var f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        f.setReadTimeout(read);
        return builder.clone().requestFactory(f).build();
    }

    static URI uri(String base, Map<String, ?> params) {
        String q = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(base + "?" + q);
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }
}
