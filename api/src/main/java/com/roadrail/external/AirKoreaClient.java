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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 에어코리아 시도별 실시간 (한도 500/일). collector 가 모으지 않는 시도만 조회 시점에 1회 받고 50분 캐시.
 * 행정구역명 → 에어코리아 시도명 변환 포함 (2026 광주·전남 통합 등 개편 반영).
 */
@Component
public class AirKoreaClient {
    private static final Logger log = LoggerFactory.getLogger(AirKoreaClient.class);
    private static final String URL = "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty";
    private static final List<String> GWANGJU_GU = List.of("동구", "서구", "남구", "북구", "광산구");
    private final RestClient http;
    private final AppProperties props;
    private final JsonCache cache;
    private final QuotaGuard quota;

    public record Air(String dataTime, Integer pm25, Integer pm25Grade, Integer khaiGrade, int stations) {}

    public AirKoreaClient(RestClient.Builder builder, AppProperties props, JsonCache cache, QuotaGuard quota) {
        this.http = Http.client(builder, Duration.ofSeconds(4));
        this.props = props;
        this.cache = cache;
        this.quota = quota;
    }

    /** 카카오 행정구역(1·2단계) → 에어코리아 sidoName */
    public static String sidoOf(String region1, String region2) {
        if (region1 == null) return null;
        String r = region1.replace(" ", "");
        if (r.startsWith("서울")) return "서울";
        if (r.startsWith("부산")) return "부산";
        if (r.startsWith("대구")) return "대구";
        if (r.startsWith("인천")) return "인천";
        if (r.startsWith("대전")) return "대전";
        if (r.startsWith("울산")) return "울산";
        if (r.startsWith("세종")) return "세종";
        if (r.startsWith("경기")) return "경기";
        if (r.startsWith("강원")) return "강원";
        if (r.startsWith("충청북") || r.startsWith("충북")) return "충북";
        if (r.startsWith("충청남") || r.startsWith("충남")) return "충남";
        if (r.startsWith("전라북") || r.startsWith("전북")) return "전북";
        if (r.startsWith("경상북") || r.startsWith("경북")) return "경북";
        if (r.startsWith("경상남") || r.startsWith("경남")) return "경남";
        if (r.startsWith("제주")) return "제주";
        if (r.startsWith("광주")) return "광주";
        if (r.startsWith("전라남") || r.startsWith("전남")) {  // '전남광주통합특별시' 포함 — 옛 광주 5개 구는 광주 측정망
            return region2 != null && GWANGJU_GU.contains(region2) ? "광주" : "전남";
        }
        return null;
    }

    public Air sido(String sido) {
        if (sido == null || props.dataGoKrKey() == null || props.dataGoKrKey().isBlank()) return null;
        return cache.get("air:" + sido, Duration.ofMinutes(50), Air.class, () -> fetch(sido)).value();
    }

    private Air fetch(String sido) {
        if (!quota.take("AIRKOREA")) return null;
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("serviceKey", props.dataGoKrKey());
            p.put("pageNo", 1);
            p.put("numOfRows", 200);
            p.put("returnType", "json");
            p.put("sidoName", sido);
            p.put("ver", "1.0");
            JsonNode body = http.get().uri(Http.uri(URL, p)).retrieve().body(JsonNode.class);
            JsonNode items = body == null ? null : body.path("response").path("body").path("items");
            if (items == null || !items.isArray() || items.isEmpty()) return null;
            List<Integer> pm = new ArrayList<>(), g = new ArrayList<>(), k = new ArrayList<>();
            String time = null;
            for (JsonNode i : items) {
                if (time == null) time = Http.text(i, "dataTime");
                add(pm, Http.text(i, "pm25Value"));
                add(g, Http.text(i, "pm25Grade"));
                add(k, Http.text(i, "khaiGrade"));
            }
            return new Air(time, median(pm), median(g), median(k), items.size());
        } catch (RuntimeException e) {
            log.warn("에어코리아 조회 실패 ({}): {}", sido, e.getClass().getSimpleName());
            return null;
        }
    }

    private static void add(List<Integer> l, String v) {
        try { if (v != null) l.add((int) Double.parseDouble(v)); } catch (NumberFormatException ignored) { /* '-' */ }
    }

    static Integer median(List<Integer> l) {
        if (l.isEmpty()) return null;
        List<Integer> s = new ArrayList<>(l);
        Collections.sort(s);
        return s.get((s.size() - 1) / 2);
    }
}
