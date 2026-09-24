package com.roadrail.external;

import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.service.JsonCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 기상청 단기예보 (getVilageFcst) — 수집 대상이 아닌 격자를 조회 시점에 받는다.
 * 발표(하루 8회)마다 격자당 1회만 호출하고 3시간 캐시. collector 와 같은 KMA 예산을 쓴다.
 */
@Component
public class KmaClient {
    private static final Logger log = LoggerFactory.getLogger(KmaClient.class);
    private static final String URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";
    private static final java.util.Set<String> KEEP = java.util.Set.of("TMP", "POP", "PTY", "SKY");
    private static final int[] BASE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};
    private static final DateTimeFormatter YMDHM = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private final RestClient http;
    private final AppProperties props;
    private final JsonCache cache;
    private final QuotaGuard quota;

    /** at(yyyyMMddHH) → {category → value} */
    public record Forecast(String baseAt, Map<String, Map<String, String>> hours) {}

    public KmaClient(RestClient.Builder builder, AppProperties props, JsonCache cache, QuotaGuard quota) {
        this.http = Http.client(builder, Duration.ofSeconds(4));
        this.props = props;
        this.cache = cache;
        this.quota = quota;
    }

    /** 발표 후 10분부터 제공 → now−15분 이전의 가장 최근 발표 시각 (collector kma.latest_base 와 같음) */
    public static OffsetDateTime latestBase(OffsetDateTime now) {
        OffsetDateTime t = Times.kst(now).minusMinutes(15);
        int best = -1;
        for (int h : BASE_HOURS) if (h <= t.getHour()) best = h;
        if (best < 0) return t.minusDays(1).withHour(23).withMinute(0).withSecond(0).withNano(0);
        return t.withHour(best).withMinute(0).withSecond(0).withNano(0);
    }

    public Forecast forecast(int nx, int ny) {
        if (props.dataGoKrKey() == null || props.dataGoKrKey().isBlank()) return null;
        OffsetDateTime base = latestBase(Times.now());
        String b = base.format(YMDHM);
        return cache.get("kma:" + nx + ":" + ny + ":" + b, Duration.ofHours(3), Forecast.class, () -> fetch(nx, ny, base)).value();
    }

    private Forecast fetch(int nx, int ny, OffsetDateTime base) {
        if (!quota.take("KMA")) return null;
        try {
            Map<String, Object> p = new java.util.LinkedHashMap<>();
            p.put("serviceKey", props.dataGoKrKey());
            p.put("pageNo", 1);
            p.put("numOfRows", 1000);
            p.put("dataType", "JSON");
            p.put("base_date", base.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            p.put("base_time", base.format(DateTimeFormatter.ofPattern("HHmm")));
            p.put("nx", nx);
            p.put("ny", ny);
            JsonNode body = http.get().uri(Http.uri(URL, p)).retrieve().body(JsonNode.class);
            JsonNode items = body == null ? null : body.path("response").path("body").path("items").path("item");
            if (items == null || !items.isArray()) return null;
            Map<String, Map<String, String>> hours = new HashMap<>();
            for (JsonNode i : items) {
                String cat = Http.text(i, "category"), date = Http.text(i, "fcstDate"), time = Http.text(i, "fcstTime");
                if (cat == null || !KEEP.contains(cat) || date == null || time == null || time.length() < 2) continue;
                String at = date + time.substring(0, 2);
                hours.computeIfAbsent(at, k -> new HashMap<>()).put(cat, Http.text(i, "fcstValue"));
            }
            return new Forecast(base.toString(), hours);
        } catch (RuntimeException e) {
            log.warn("단기예보 조회 실패 ({},{}): {}", nx, ny, e.getClass().getSimpleName());
            return null;
        }
    }

    public static String hourKey(OffsetDateTime t) {
        LocalDateTime k = Times.kst(t).toLocalDateTime();
        return k.format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
    }
}
