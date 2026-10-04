package com.roadrail.external;

import com.roadrail.shared.Times;
import com.roadrail.shared.AppProperties;
import com.roadrail.shared.JsonCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 기상청 동네예보 서비스 — 수집 대상이 아닌 격자를 조회 시점에 받는다. collector 와 같은 KMA 예산을 쓴다.
 *  · 단기예보(getVilageFcst): 하루 8회 발표 · 격자당 발표마다 1회 · 3시간 캐시 — 강수확률(POP)은 여기에만 있다
 *  · 초단기예보(getUltraSrtFcst): 매시 30분 발표 · 6시간 앞까지 — 가까운 출발 시각의 기온 · 하늘 · 강수형태 · 1시간 강수량
 *  · 초단기실황(getUltraSrtNcst): 매시 정각 관측 — '지금 비 · 눈이 오는지'
 * 같은 서비스의 기능이라 활용 신청 · 키가 같다. 초단기는 발표마다 키가 바뀌어 한 시간에 격자당 1회만 부른다.
 */
@Component
public class KmaClient {
    private static final Logger log = LoggerFactory.getLogger(KmaClient.class);
    private static final String URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/";
    private static final Set<String> KEEP = Set.of("TMP", "POP", "PTY", "SKY");          // 단기예보
    private static final Set<String> KEEP_ULTRA = Set.of("T1H", "SKY", "PTY", "RN1");   // 초단기예보
    private static final Set<String> KEEP_NOW = Set.of("T1H", "PTY", "RN1");            // 초단기실황
    private static final int[] BASE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};
    private static final DateTimeFormatter YMDHM = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private final RestClient http;
    private final AppProperties props;
    private final JsonCache cache;
    private final QuotaGuard quota;

    /** at(yyyyMMddHH) → {category → value} */
    public record Forecast(String baseAt, Map<String, Map<String, String>> hours) {}

    /** 초단기실황: category → 관측값 */
    public record Observation(String baseAt, Map<String, String> values) {}

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

    /** 초단기예보 — 매시 30분 발표 · 45분부터 제공(활용가이드) → now−15분 이전의 가장 최근 HH:30 */
    public static OffsetDateTime ultraBase(OffsetDateTime now) {
        OffsetDateTime t = Times.kst(now).minusMinutes(15);
        OffsetDateTime b = t.withMinute(30).withSecond(0).withNano(0);
        return b.isAfter(t) ? b.minusHours(1) : b;
    }

    /** 초단기실황 — 매시 정각 관측 · 10분부터 제공(활용가이드) → now−10분 이전의 가장 최근 정각 */
    public static OffsetDateTime nowcastBase(OffsetDateTime now) {
        return Times.kst(now).minusMinutes(10).truncatedTo(ChronoUnit.HOURS);
    }

    private boolean enabled() {
        return props.dataGoKrKey() != null && !props.dataGoKrKey().isBlank();
    }

    public Forecast forecast(int nx, int ny) {
        return forecast(nx, ny, latestBase(Times.now()));
    }

    /** 특정 발표 — 새 발표는 발표 +1시간부터라(17시 발표 → 18시부터) 지금 시간대는 직전 발표에서 읽는다 */
    public Forecast forecast(int nx, int ny, OffsetDateTime base) {
        if (!enabled()) return null;
        String b = base.format(YMDHM);
        return cache.get("kma:" + nx + ":" + ny + ":" + b, Duration.ofHours(3), Forecast.class,
                () -> forecastItems(fetch("getVilageFcst", nx, ny, base, 1000), base, KEEP)).value();
    }

    /** 초단기예보 6시간 — 발표마다 키가 바뀐다(75분 캐시: 다음 발표를 받을 때까지) */
    public Forecast ultraShort(int nx, int ny) {
        if (!enabled()) return null;
        OffsetDateTime base = ultraBase(Times.now());
        return cache.get("kma:u:" + nx + ":" + ny + ":" + base.format(YMDHM), Duration.ofMinutes(75), Forecast.class,
                () -> forecastItems(fetch("getUltraSrtFcst", nx, ny, base, 100), base, KEEP_ULTRA)).value();
    }

    /** 초단기실황 — 발표마다 키가 바뀐다 */
    public Observation nowcast(int nx, int ny) {
        if (!enabled()) return null;
        OffsetDateTime base = nowcastBase(Times.now());
        return cache.get("kma:n:" + nx + ":" + ny + ":" + base.format(YMDHM), Duration.ofMinutes(75), Observation.class,
                () -> observation(fetch("getUltraSrtNcst", nx, ny, base, 20), base)).value();
    }

    /** 응답의 item 배열. 오류 응답(resultCode ≠ 00)이나 빈 응답은 null — 캐시하지 않는다 */
    private JsonNode fetch(String op, int nx, int ny, OffsetDateTime base, int rows) {
        if (!quota.take("KMA")) return null;
        try {
            Map<String, Object> p = new java.util.LinkedHashMap<>();
            p.put("serviceKey", props.dataGoKrKey());
            p.put("pageNo", 1);
            p.put("numOfRows", rows);
            p.put("dataType", "JSON");
            p.put("base_date", base.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            p.put("base_time", base.format(DateTimeFormatter.ofPattern("HHmm")));
            p.put("nx", nx);
            p.put("ny", ny);
            return items(http.get().uri(Http.uri(URL + op, p)).retrieve().body(JsonNode.class));
        } catch (RuntimeException e) {
            log.warn("기상청 {} 조회 실패 ({},{}): {}", op, nx, ny, e.getClass().getSimpleName());
            return null;
        }
    }

    static JsonNode items(JsonNode body) {
        if (body == null || !"00".equals(Http.text(body.path("response").path("header"), "resultCode"))) return null;
        JsonNode items = body.path("response").path("body").path("items").path("item");
        return items.isArray() && !items.isEmpty() ? items : null;
    }

    /** 예보 item(fcstDate · fcstTime · category · fcstValue) → 시각(yyyyMMddHH)별 값 */
    static Forecast forecastItems(JsonNode items, OffsetDateTime base, Set<String> keep) {
        if (items == null) return null;
        Map<String, Map<String, String>> hours = new HashMap<>();
        for (JsonNode i : items) {
            String cat = Http.text(i, "category"), date = Http.text(i, "fcstDate"), time = Http.text(i, "fcstTime");
            if (cat == null || !keep.contains(cat) || date == null || time == null || time.length() < 2) continue;
            hours.computeIfAbsent(date + time.substring(0, 2), k -> new HashMap<>()).put(cat, Http.text(i, "fcstValue"));
        }
        return hours.isEmpty() ? null : new Forecast(base.toString(), hours);
    }

    /** 실황 item(category · obsrValue) → 값 */
    static Observation observation(JsonNode items, OffsetDateTime base) {
        if (items == null) return null;
        Map<String, String> values = new HashMap<>();
        for (JsonNode i : items) {
            String cat = Http.text(i, "category");
            if (cat != null && KEEP_NOW.contains(cat)) values.put(cat, Http.text(i, "obsrValue"));
        }
        return values.isEmpty() ? null : new Observation(base.toString(), values);
    }

    public static String hourKey(OffsetDateTime t) {
        LocalDateTime k = Times.kst(t).toLocalDateTime();
        return k.format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
    }
}
