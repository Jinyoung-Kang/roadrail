package com.roadrail.web;

import com.roadrail.common.ApiException;
import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.service.*;
import com.roadrail.web.dto.CorridorDtos.Corridor;
import com.roadrail.web.dto.EnvDtos;
import com.roadrail.web.dto.NowDtos.NowCard;
import com.roadrail.web.dto.RailDtos;
import com.roadrail.web.dto.RoadDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "corridors", description = "코리도 · 판단 카드 · 도로 · 철도 · 환경")
public class CorridorController {
    private final CorridorService corridors;
    private final NowCardService now;
    private final RoadService road;
    private final RailService rail;
    private final EnvService env;
    private final AppProperties props;

    public CorridorController(CorridorService corridors, NowCardService now, RoadService road, RailService rail,
                              EnvService env, AppProperties props) {
        this.corridors = corridors;
        this.now = now;
        this.road = road;
        this.rail = rail;
        this.env = env;
        this.props = props;
    }

    @GetMapping("/corridors")
    @Operation(summary = "코리도 목록 (FR-102) — 도로 구간 체인 · 역 쌍 · 환경 지점 좌표 포함")
    public List<Corridor> list() {
        return corridors.list();
    }

    @GetMapping("/corridors/{id}/now")
    @Operation(summary = "판단 카드 (FR-501~504) — 도로·철도·환경·돌발 + R-DEC-01 요약과 근거")
    public ResponseEntity<NowCard> now(@PathVariable String id, @RequestParam String dir,
                                       @RequestParam(defaultValue = "0") @Min(0) @Max(360) int departIn,
                                       @RequestParam(required = false) @Min(0) @Max(180) Integer accessMin,
                                       @RequestParam(defaultValue = "0") @Min(0) @Max(180) int carAccessMin) {
        corridors.require(id);
        NowCard card = now.now(id, CorridorService.dir(dir), departIn,
                accessMin == null ? props.defaultAccessMin() : accessMin, carAccessMin);
        // 도로 최신 슬롯이 허용 신선도를 넘으면 카드는 반환하되 status 로 표시 (7-3)
        return ResponseEntity.ok().header("X-Cache", card.cache()).body(card);
    }

    @GetMapping("/corridors/{id}/road/series")
    @Operation(summary = "통행시간 시계열 (FR-601) — agg=5m|1h, 강수 예보·카카오 ETA 동반")
    public RoadDtos.Series series(@PathVariable String id, @RequestParam String dir,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                  @RequestParam(defaultValue = "5m") String agg) {
        corridors.require(id);
        OffsetDateTime t = to != null ? to : Times.now();
        OffsetDateTime f = from != null ? from : t.minusDays(2);
        return road.series(id, CorridorService.dir(dir), f, t, agg);
    }

    @GetMapping("/corridors/{id}/road/baseline")
    @Operation(summary = "요일×슬롯 기준선 (FR-401, 601) — 히트맵용")
    public RoadDtos.Baseline baseline(@PathVariable String id, @RequestParam String dir) {
        corridors.require(id);
        return road.baseline(id, CorridorService.dir(dir));
    }

    @GetMapping("/corridors/{id}/road/forecast")
    @Operation(summary = "60·120·180분 예측 (M0·M1·지속) + 최근 백테스트 (FR-403~404)")
    public RoadDtos.Forecast forecast(@PathVariable String id, @RequestParam String dir,
                                      @RequestParam(defaultValue = "60,120,180") List<Integer> horizons) {
        corridors.require(id);
        if (horizons.isEmpty() || horizons.size() > 12 || horizons.stream().anyMatch(h -> h < 0 || h > 720)) {
            throw ApiException.invalid("horizons 는 0~720 분, 최대 12개입니다.");
        }
        return road.forecast(id, CorridorService.dir(dir), horizons);
    }

    @GetMapping("/corridors/{id}/rail/trains")
    @Operation(summary = "날짜별 코리도 열차 + 열차별 최근 30일 정시성 (FR-302)")
    public RailDtos.Trains trains(@PathVariable String id, @RequestParam(defaultValue = "DN") String dir,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        corridors.require(id);
        return rail.trains(id, CorridorService.dir(dir), date);
    }

    @GetMapping("/corridors/{id}/env")
    @Operation(summary = "출발·도착 지점 단기예보(시간별) · 대기질 (FR-501)")
    public EnvDtos.Env env(@PathVariable String id, @RequestParam(defaultValue = "24") @Min(1) @Max(72) int hours) {
        corridors.require(id);
        return env.env(id, hours);
    }
}
