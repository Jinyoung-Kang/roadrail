package com.roadrail.web;

import com.roadrail.common.ApiException;
import com.roadrail.service.PlaceService;
import com.roadrail.service.RailService;
import com.roadrail.service.RoadRouteService;
import com.roadrail.service.TripService;
import com.roadrail.web.dto.RailDtos;
import com.roadrail.web.dto.TripDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "trip", description = "어디서 → 어디로: 전국 임의의 두 지점 · 모든 기차역 쌍")
public class TripController {
    private final TripService trips;
    private final PlaceService places;
    private final RailService rail;
    private final RoadRouteService roads;

    public TripController(TripService trips, PlaceService places, RailService rail, RoadRouteService roads) {
        this.trips = trips;
        this.places = places;
        this.rail = rail;
        this.roads = roads;
    }

    @GetMapping("/road/route")
    @Operation(summary = "도로 분석 — 전국 임의 두 지점: 도로별 구성 · 느린 구간 · 고속도로 회피 비교 · 출발 시각별 소요")
    public com.roadrail.web.dto.RouteDtos.Analysis route(@RequestParam double fromLat, @RequestParam double fromLon,
                                                          @RequestParam @Size(max = 60) String fromName,
                                                          @RequestParam double toLat, @RequestParam double toLon,
                                                          @RequestParam @Size(max = 60) String toName,
                                                          @RequestParam(defaultValue = "0") @Min(0) @Max(360) int departIn) {
        var from = new TripDtos.Place(fromName, null, fromLat, fromLon, "PLACE", null);
        var to = new TripDtos.Place(toName, null, toLat, toLon, "PLACE", null);
        TripService.validate(from);
        TripService.validate(to);
        return roads.analyze(from, to, departIn);
    }

    @GetMapping("/places/search")
    @Operation(summary = "어디서 · 어디로 검색 — 행정구역 · 기차역 · 장소 (전국)")
    public TripDtos.PlaceSearch search(@RequestParam @Size(min = 1, max = 40) String q) {
        if (q.isBlank()) throw ApiException.invalid("검색어를 입력하세요.");
        return places.search(q);
    }

    @GetMapping("/stations")
    @Operation(summary = "운행 중인 기차역 검색 (sort=trains: 정확 일치·정차 편수 순, sort=name: 가나다순)")
    public List<RailDtos.Station> stations(@RequestParam(defaultValue = "") @Size(max = 20) String q,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(400) int limit,
                                           @RequestParam(defaultValue = "trains") @jakarta.validation.constraints.Pattern(regexp = "trains|name") String sort) {
        return rail.stations(q, limit, "name".equals(sort));
    }

    @GetMapping("/trip")
    @Operation(summary = "출발지 → 도착지 판단 카드 — 카카오 경로(자동차) · 근처 역 직통 열차 · 날씨·대기 · R-DEC-01")
    public ResponseEntity<TripDtos.Trip> trip(@RequestParam double fromLat, @RequestParam double fromLon,
                                              @RequestParam @Size(max = 60) String fromName,
                                              @RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = STATION) String fromStation,
                                              @RequestParam double toLat, @RequestParam double toLon,
                                              @RequestParam @Size(max = 60) String toName,
                                              @RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = STATION) String toStation,
                                              @RequestParam(defaultValue = "0") @Min(0) @Max(360) int departIn,
                                              @RequestParam(required = false) @Min(0) @Max(180) Integer accessMin) {
        var from = new TripDtos.Place(fromName, null, fromLat, fromLon, fromStation == null ? "PLACE" : "STATION", blank(fromStation));
        var to = new TripDtos.Place(toName, null, toLat, toLon, toStation == null ? "PLACE" : "STATION", blank(toStation));
        var t = trips.trip(from, to, departIn, accessMin);
        return ResponseEntity.ok().header("X-Cache", t.cache()).body(t);
    }

    @GetMapping("/rail/od/punctuality")
    @Operation(summary = "임의 역 쌍 정시율 — groupBy=train|dow|hour, thresholdMin")
    public RailDtos.Punctuality odPunctuality(@RequestParam @jakarta.validation.constraints.Pattern(regexp = STATION) String dep, @RequestParam @jakarta.validation.constraints.Pattern(regexp = STATION) String arr,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(defaultValue = "train") @jakarta.validation.constraints.Pattern(regexp = "train|dow|hour") String groupBy,
                                              @RequestParam(required = false) Integer thresholdMin) {
        return rail.punctuality(dep, arr, from, to, groupBy, thresholdMin);
    }

    @GetMapping("/rail/od/trains")
    @Operation(summary = "임의 역 쌍 날짜별 열차 + 열차별 최근 30일 정시성")
    public RailDtos.Trains odTrains(@RequestParam @jakarta.validation.constraints.Pattern(regexp = STATION) String dep, @RequestParam @jakarta.validation.constraints.Pattern(regexp = STATION) String arr,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (dep.equals(arr)) throw ApiException.invalid("출발역과 도착역이 같습니다.");
        return rail.trains(dep, arr, date);
    }

    /** 역 코드 — 캐시 키 · SQL 매개변수로 쓰이므로 형식 · 길이를 제한 (코레일 7자리, 테스트 S1) */
    static final String STATION = "[0-9A-Za-z]{0,10}";

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
