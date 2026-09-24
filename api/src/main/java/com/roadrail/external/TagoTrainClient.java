package com.roadrail.external;

import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.service.JsonCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 국토교통부(TAGO) 열차정보 — 역 쌍 · 날짜별 시간표(출/도착지기반열차정보).
 * 열차 번호마다 **그날 실제로 배정된 차종**(traingradename: KTX · KTX-산천 · ITX-새마을 · 무궁화호 …)과
 * 출발역 계획 출발 · 도착역 계획 도착을 준다 → 중간역도 계획 시각을 알 수 있다.
 * 실측(2026-09-25): 지난 날짜는 전체 시간표가 조회되지만 당일은 일부 · 미래는 0건 → 지난 운행 분석과 기준일 시간표에만 쓴다.
 * 열차 번호 형식은 코레일과 같다(00201). 개발계정 한도 10,000건/일.
 */
@Component
public class TagoTrainClient {
    private static final Logger log = LoggerFactory.getLogger(TagoTrainClient.class);
    private static final String BASE = "https://apis.data.go.kr/1613000/TrainInfo";
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter YMDHMS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 시도 코드 (GetCtyCodeList, 2026-09-25) */
    private static final List<String> CITIES = List.of("11", "12", "21", "22", "23", "24", "25", "26", "31", "32", "33", "34",
            "35", "36", "37", "38");
    /**
     * 코레일 역명 → TAGO 역명이 다른 경우. 추정이 아니라 검증한 것만:
     * 여수엑스포 = 여수EXPO (2026-09-18 용산→여수 시간표의 열차 번호 · 출발 · 도착 시각이 코레일 운행계획과 모두 일치).
     */
    static final Map<String, String> ALIAS = Map.of("여수엑스포", "여수EXPO");

    /** 초당 호출 한도가 있어(순간 16건 이상에서 429 실측) 호출 사이 최소 간격을 둔다 → 초당 최대 10건 */
    private static final long SPACING_NANOS = 100_000_000L;
    private final RestClient http;
    private final AppProperties props;
    private final JsonCache cache;
    private final QuotaGuard quota;
    private final java.util.concurrent.locks.ReentrantLock nodesLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.ReentrantLock paceLock = new java.util.concurrent.locks.ReentrantLock();
    private long nextSlot = 0;

    public record Nodes(Map<String, String> byName) {}

    /** 한 열차의 시간표 — planDep = 출발역 계획 출발, planArr = 도착역 계획 도착, grade = 그날 배정 차종 */
    public record Plan(String trnNo, String grade, OffsetDateTime planDep, OffsetDateTime planArr) {}

    public TagoTrainClient(RestClient.Builder builder, AppProperties props, JsonCache cache, QuotaGuard quota) {
        this.http = Http.client(builder, Duration.ofSeconds(5));
        this.props = props;
        this.cache = cache;
        this.quota = quota;
    }

    public boolean enabled() {
        return props.dataGoKrKey() != null && !props.dataGoKrKey().isBlank();
    }

    /** 역명 → TAGO 역 ID (전국 16개 시도 목록, 7일 캐시). 실패하면 빈 값 */
    public Map<String, String> nodes() {
        if (!enabled()) return Map.of();
        Nodes hit = cache.peek("tago:train:nodes", Nodes.class);
        if (hit != null) return hit.byName();
        nodesLock.lock();  // 여러 요청이 동시에 처음 부르면 한 번만 불러온다
        try {
            return loadNodes();
        } finally {
            nodesLock.unlock();
        }
    }

    private Map<String, String> loadNodes() {
        var h = cache.get("tago:train:nodes", Duration.ofDays(7), Nodes.class, () -> {
            Map<String, String> m = new HashMap<>();
            for (String c : CITIES) {
                JsonNode items = call("/GetCtyAcctoTrainSttnList", Map.of("cityCode", c, "numOfRows", 500));
                if (items == null) return null;  // 하나라도 실패하면 캐시하지 않음
                for (JsonNode i : items) m.put(Http.text(i, "nodename"), Http.text(i, "nodeid"));
            }
            return new Nodes(m);
        });
        return h.value() == null ? Map.of() : h.value().byName();
    }

    public String nodeId(String stationName) {
        return nodes().get(ALIAS.getOrDefault(stationName, stationName));
    }

    /** depNode → arrNode, 출발역 출발 날짜 date 의 열차들. 호출 실패면 null (빈 목록과 구분) */
    public List<Plan> plans(String depNode, String arrNode, LocalDate date) {
        JsonNode items = call("/GetStrtpntAlocFndTrainInfo", Map.of("depPlaceId", depNode, "arrPlaceId", arrNode,
                "depPlandTime", date.format(YMD), "numOfRows", 500));
        if (items == null) return null;
        Map<String, Plan> out = new LinkedHashMap<>();  // 응답에 같은 열차가 두 번 오는 경우가 있다
        for (JsonNode i : items) {
            String no = Http.text(i, "trainno"), dep = Http.text(i, "depplandtime"), arr = Http.text(i, "arrplandtime");
            if (no == null || dep == null || arr == null) continue;
            try {
                out.put(no, new Plan(no, Http.text(i, "traingradename"), at(dep), at(arr)));
            } catch (RuntimeException e) {
                log.debug("TAGO 시각 형식 오류 {} {}", dep, arr);
            }
        }
        return new ArrayList<>(out.values());
    }

    static OffsetDateTime at(String yyyyMMddHHmmss) {
        return LocalDateTime.parse(yyyyMMddHHmmss, YMDHMS).atZone(Times.KST).toOffsetDateTime();
    }

    private void pace() {
        paceLock.lock();
        try {
            long now = System.nanoTime(), wait = nextSlot - now;
            nextSlot = Math.max(now, nextSlot) + SPACING_NANOS;
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
        } finally {
            paceLock.unlock();
        }
    }

    private JsonNode call(String path, Map<String, Object> params) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return callOnce(path, params);
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                log.debug("TAGO 열차 {} 429 — {}초 뒤 다시", path, attempt + 1);
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(attempt + 1).toNanos());
            }
        }
        log.warn("TAGO 열차 {} 실패: 초당 호출 한도 초과가 계속됨", path);
        return null;
    }

    private JsonNode callOnce(String path, Map<String, Object> params) {
        pace();
        if (!quota.take("TAGO_TRAIN")) return null;
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("serviceKey", props.dataGoKrKey());
            p.put("_type", "json");
            p.put("pageNo", 1);
            p.putAll(params);
            JsonNode body = http.get().uri(Http.uri(BASE + path, p)).retrieve().body(JsonNode.class);
            JsonNode header = body == null ? null : body.path("response").path("header");
            if (header == null || !"00".equals(Http.text(header, "resultCode"))) {
                log.warn("TAGO 열차 {} 응답 오류: {}", path, header == null ? "빈 응답" : Http.text(header, "resultMsg"));
                return null;
            }
            JsonNode items = body.path("response").path("body").path("items").path("item");
            if (items.isMissingNode() || items.isNull()) return JsonNodeFactory.instance.arrayNode();
            if (items.isObject()) return JsonNodeFactory.instance.arrayNode().add(items);
            return items;
        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("TAGO 열차 {} 실패: {}", path, e.getClass().getSimpleName());
            return null;
        }
    }
}
