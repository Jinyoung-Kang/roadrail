package com.roadrail.web;

import com.roadrail.common.Times;
import com.roadrail.web.dto.OpsDtos;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;

    public HealthController(JdbcClient jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping("/api/v1/health")
    @Operation(summary = "DB · Redis · 수집기(스케줄러 heartbeat) 상태 (FR-702)")
    public ResponseEntity<OpsDtos.Health> health() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("db", check(() -> jdbc.sql("SELECT 1").query(Integer.class).single() == 1));
        c.put("redis", check(() -> "PONG".equalsIgnoreCase(redis.getConnectionFactory().getConnection().ping())));
        c.put("collector", check(() -> redis.hasKey("rr:collector:heartbeat")));
        String status = c.get("db").equals("UP") ? (c.containsValue("DOWN") ? "DEGRADED" : "UP") : "DOWN";
        return ResponseEntity.status("DOWN".equals(status) ? 503 : 200).body(new OpsDtos.Health(status, c, Times.now()));
    }

    private static String check(java.util.function.BooleanSupplier s) {
        try { return s.getAsBoolean() ? "UP" : "DOWN"; } catch (RuntimeException e) { return "DOWN"; }
    }
}
