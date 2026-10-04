package com.roadrail.corridor.web;

import com.roadrail.shared.Times;
import com.roadrail.corridor.app.CorridorService;
import com.roadrail.env.app.EnvService;
import com.roadrail.rail.app.RailService;
import com.roadrail.env.model.EnvDtos;
import com.roadrail.rail.model.RailDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "analysis", description = "정시율 · 돌발 · 수집 상태")
/** 길 단위 분석 조회 — 길의 역 쌍 정시율 · 돌발 안내 */
public class QueryController {
    private final CorridorService corridors;
    private final RailService rail;
    private final EnvService env;

    public QueryController(CorridorService corridors, RailService rail, EnvService env) {
        this.corridors = corridors;
        this.rail = rail;
        this.env = env;
    }

    @GetMapping("/rail/punctuality")
    @Operation(summary = "정시율 집계 — 길의 역 쌍 (FR-302~303). 임의 역 쌍은 /rail/od/punctuality")
    public RailDtos.Punctuality punctuality(@RequestParam @jakarta.validation.constraints.Pattern(regexp = "[A-Z0-9-]{3,20}") String corridorId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) String dir,
                                            @RequestParam(defaultValue = "train") String groupBy,
                                            @RequestParam(required = false) Integer thresholdMin) {
        corridors.require(corridorId);
        var pair = rail.pairOf(corridorId, dir == null ? "DN" : CorridorService.dir(dir));
        return rail.punctuality(pair.dep(), pair.arr(), from, to, groupBy, thresholdMin);
    }

    @GetMapping("/incidents")
    @Operation(summary = "돌발 문자 안내 (FR-405) — 길(corridorId) 매칭, since 이후")
    public EnvDtos.Incidents incidents(@RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = "[A-Z0-9-]{3,20}") String corridorId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
                                       @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        if (corridorId != null) corridors.require(corridorId);
        return env.incidents(corridorId, since != null ? since : Times.now().minusHours(24), limit);
    }
}
