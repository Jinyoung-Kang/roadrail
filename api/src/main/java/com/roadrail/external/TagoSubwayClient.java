package com.roadrail.external;

import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.service.JsonCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 국토교통부(TAGO) 지하철정보 — 역 검색 · 역별 시간표.
 * 이 API 는 역별 출발 시각과 종착역만 주고 열차 번호 · 역 순서 · 좌표가 없어 지하철 경로 탐색에는 쓸 수 없다.
 * 그래서 기차역에서 **갈아탈 수 있는 지하철 노선과 다음 열차 시각**을 안내하는 데 쓴다.
 */
@Component
public class TagoSubwayClient {
    private static final Logger log = LoggerFactory.getLogger(TagoSubwayClient.class);
    private static final String BASE = "https://apis.data.go.kr/1613000/SubwayInfo";
    private final RestClient http;
    private final AppProperties props;
    private final JsonCache cache;
    private final QuotaGuard quota;

    public record SubwayStation(String id, String name, String line) {}

    public record Stations(List<SubwayStation> items) {}

    public record Departure(String line, String toward, LocalTime dep) {}

    public record Timetable(List<Departure> items) {}

    /** 한 노선·방향의 다음 열차 */
    public record NextSubway(String line, String toward, List<String> times) {}

    public TagoSubwayClient(RestClient.Builder builder, AppProperties props, JsonCache cache, QuotaGuard quota) {
        this.http = Http.client(builder, Duration.ofSeconds(4));
        this.props = props;
        this.cache = cache;
        this.quota = quota;
    }

    static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\(.*?\\)", "").replaceAll("역$", "").replace(" ", "");
    }

    /** 기차역 이름과 같은 이름의 지하철역들 (노선별) — 7일 캐시 */
    public List<SubwayStation> stationsNamed(String name) {
        if (props.dataGoKrKey() == null || props.dataGoKrKey().isBlank()) return List.of();
        var h = cache.get("tago:stn:" + norm(name), Duration.ofDays(7), Stations.class, () -> {
            JsonNode items = call("/GetKwrdFndSubwaySttnList", Map.of("subwayStationName", norm(name), "numOfRows", 50));
            if (items == null) return null;
            List<SubwayStation> out = new ArrayList<>();
            for (JsonNode i : items) {
                if (norm(Http.text(i, "subwayStationName")).equals(norm(name))) {
                    out.add(new SubwayStation(Http.text(i, "subwayStationId"), Http.text(i, "subwayStationName"), Http.text(i, "subwayRouteName")));
                }
            }
            return new Stations(out);
        });
        return h.value() == null ? List.of() : h.value().items();
    }

    /** 요일 구분: 01 평일 · 02 토요일 · 03 일요일(공휴일은 구분하지 않음) */
    public static String dailyType(OffsetDateTime t) {
        DayOfWeek d = Times.kst(t).getDayOfWeek();
        return d == DayOfWeek.SATURDAY ? "02" : d == DayOfWeek.SUNDAY ? "03" : "01";
    }

    public List<Departure> timetable(SubwayStation st, String dailyType, String upDown) {
        var h = cache.get("tago:tt:" + st.id() + ":" + dailyType + ":" + upDown, Duration.ofDays(1), Timetable.class, () -> {
            JsonNode items = call("/GetSubwaySttnAcctoSchdulList", Map.of("subwayStationId", st.id(), "dailyTypeCode", dailyType,
                    "upDownTypeCode", upDown, "numOfRows", 400));
            if (items == null) return null;
            List<Departure> out = new ArrayList<>();
            for (JsonNode i : items) {
                String dep = Http.text(i, "depTime");
                if (dep == null || dep.length() < 4) continue;
                int hh = Integer.parseInt(dep.substring(0, 2)) % 24, mm = Integer.parseInt(dep.substring(2, 4));
                out.add(new Departure(st.line(), Http.text(i, "endSubwayStationNm"), LocalTime.of(hh, mm)));
            }
            out.sort(Comparator.comparing(Departure::dep));
            return new Timetable(out);
        });
        return h.value() == null ? List.of() : h.value().items();
    }

    /** 기차역(이름)에서 after 이후 갈아탈 수 있는 지하철: 노선 · 방향(종착역)별 다음 2편 */
    public List<NextSubway> next(String stationName, OffsetDateTime after) {
        String dt = dailyType(after);
        LocalTime from = Times.kst(after).toLocalTime();
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (SubwayStation st : stationsNamed(stationName)) {
            for (String ud : List.of("U", "D")) {
                for (Departure d : timetable(st, dt, ud)) {
                    if (d.dep().isBefore(from) || d.dep().isAfter(from.plusHours(2))) continue;  // 자정 넘김은 단순화
                    List<String> l = byKey.computeIfAbsent(d.line() + "|" + d.toward(), k -> new ArrayList<>());
                    if (l.size() < 2) l.add(d.dep().format(Times.HM));
                }
            }
        }
        List<NextSubway> out = new ArrayList<>();
        byKey.forEach((k, v) -> out.add(new NextSubway(k.split("\\|")[0], k.split("\\|", 2)[1] + "행", v)));
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    private JsonNode call(String path, Map<String, Object> params) {
        if (!quota.take("TAGO")) return null;
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("serviceKey", props.dataGoKrKey());
            p.put("_type", "json");
            p.put("pageNo", 1);
            p.putAll(params);
            JsonNode body = http.get().uri(Http.uri(BASE + path, p)).retrieve().body(JsonNode.class);
            JsonNode items = body == null ? null : body.path("response").path("body").path("items").path("item");
            if (items == null || items.isMissingNode()) return tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            if (items.isObject()) return tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(items);
            return items;
        } catch (RuntimeException e) {
            log.warn("TAGO 지하철 {} 실패: {}", path, e.getClass().getSimpleName());
            return null;
        }
    }
}
