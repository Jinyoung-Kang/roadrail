package com.roadrail.web;

import com.roadrail.common.Times;
import com.roadrail.service.CorridorService;
import com.roadrail.service.EnvService;
import com.roadrail.service.OpsService;
import com.roadrail.service.RailService;
import com.roadrail.web.dto.EnvDtos;
import com.roadrail.web.dto.OpsDtos;
import com.roadrail.web.dto.RailDtos;
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
public class QueryController {
    private final CorridorService corridors;
    private final RailService rail;
    private final EnvService env;
    private final OpsService ops;

    public QueryController(CorridorService corridors, RailService rail, EnvService env, OpsService ops) {
        this.corridors = corridors;
        this.rail = rail;
        this.env = env;
        this.ops = ops;
    }

    @GetMapping("/rail/punctuality")
    @Operation(summary = "정시율 집계 (FR-302~303) — groupBy=train|dow|hour, thresholdMin")
    public RailDtos.Punctuality punctuality(@RequestParam String corridorId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) String dir,
                                            @RequestParam(defaultValue = "train") String groupBy,
                                            @RequestParam(required = false) Integer thresholdMin) {
        corridors.require(corridorId);
        return rail.punctuality(corridorId, dir == null ? null : CorridorService.dir(dir), from, to, groupBy, thresholdMin);
    }

    @GetMapping("/incidents")
    @Operation(summary = "돌발 문자 안내 (FR-405) — corridorId 매칭, since 이후")
    public EnvDtos.Incidents incidents(@RequestParam(required = false) String corridorId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
                                       @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        if (corridorId != null) corridors.require(corridorId);
        return env.incidents(corridorId, since != null ? since : Times.now().minusHours(24), limit);
    }

    @GetMapping("/ops/collect-status")
    @Operation(summary = "수집 상태 (FR-701) — 작업별 최근 실행 · 24h 완전성 · 예산 · 공개 지연 · 최근 오류")
    public OpsDtos.Status collectStatus() {
        return ops.status();
    }
}
