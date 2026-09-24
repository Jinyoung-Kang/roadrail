package com.roadrail.web;

import com.roadrail.service.AdminService;
import com.roadrail.web.dto.OpsDtos;
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

    public AdminController(AdminService admin) { this.admin = admin; }

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
