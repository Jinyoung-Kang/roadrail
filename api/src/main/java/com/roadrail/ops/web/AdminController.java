package com.roadrail.ops.web;

import com.roadrail.ops.app.AdminService;
import com.roadrail.ops.app.OpsService;
import com.roadrail.ops.model.OpsDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin", description = "X-Admin-Token 필수")
public class AdminController {
    private final AdminService admin;
    private final OpsService ops;

    public AdminController(AdminService admin, OpsService ops) {
        this.admin = admin;
        this.ops = ops;
    }

    @GetMapping("/collect-status")
    @Parameters(@Parameter(name = "X-Admin-Token", in = ParameterIn.HEADER, required = true))
    @Operation(summary = "수집 상태 전체 — 공개 경로가 비우는 오류 상세(메시지 · 스택 트레이스 · 외부 호출 주소 · 오류 문구) 포함")
    public OpsDtos.Status collectStatus() {
        return ops.status(true);
    }

    @PostMapping("/jobs/{job}/run")
    @Parameters(@Parameter(name = "X-Admin-Token", in = ParameterIn.HEADER, required = true))
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "작업 즉시 실행 (FR-201) — 202, 실행 중이면 409 JOB_RUNNING")
    public OpsDtos.Accepted run(@PathVariable String job) {
        return admin.runJob(job);
    }

    @PostMapping("/backfill")
    @Parameters(@Parameter(name = "X-Admin-Token", in = ParameterIn.HEADER, required = true))
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "기간 재수집 (FR-205) — 예상 호출 수·예산을 먼저 계산. 400 기간 · 429 예산 · 409 실행 중")
    public OpsDtos.BackfillAccepted backfill(@RequestBody OpsDtos.BackfillRequest req) {
        return admin.backfill(req);
    }
}
