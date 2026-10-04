package com.roadrail.ops.web;

import com.roadrail.ops.app.HealthService;
import com.roadrail.ops.model.OpsDtos;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final HealthService health;

    public HealthController(HealthService health) {
        this.health = health;
    }

    @GetMapping("/api/v1/health")
    @Operation(summary = "DB · Redis · 수집기(스케줄러 heartbeat) 상태 (FR-702)")
    public ResponseEntity<OpsDtos.Health> health() {
        OpsDtos.Health h = health.health();
        return ResponseEntity.status("DOWN".equals(h.status()) ? 503 : 200).body(h);
    }
}
