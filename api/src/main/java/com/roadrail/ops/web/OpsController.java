package com.roadrail.ops.web;

import com.roadrail.ops.app.OpsService;
import com.roadrail.ops.model.OpsDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 수집 상태 조회 — 예전 QueryController 에 있던 경로 그대로 (문서 묶음 analysis 유지) */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "analysis", description = "정시율 · 돌발 · 수집 상태")
public class OpsController {
    private final OpsService ops;

    public OpsController(OpsService ops) {
        this.ops = ops;
    }

    @GetMapping("/ops/collect-status")
    @Operation(summary = "수집 상태 (FR-701) — 작업별 최근 실행 · 24h 완전성 · 예산 · 공개 지연 · 최근 오류")
    public OpsDtos.Status collectStatus() {
        return ops.status();
    }
}
