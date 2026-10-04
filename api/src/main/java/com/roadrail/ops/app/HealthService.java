package com.roadrail.ops.app;

import com.roadrail.ops.data.OpsRepository;
import com.roadrail.ops.model.OpsDtos;
import com.roadrail.shared.Times;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** DB · Redis · 수집기(스케줄러 heartbeat) 상태 (FR-702) */
@Service
public class HealthService {
    private final OpsRepository repo;
    private final StringRedisTemplate redis;

    public HealthService(OpsRepository repo, StringRedisTemplate redis) {
        this.repo = repo;
        this.redis = redis;
    }

    public OpsDtos.Health health() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("db", check(repo::ping));
        // execute(콜백)은 연결을 빌려 쓰고 반납한다 — getConnection() 을 직접 열면 닫지 않는 한 샌다
        c.put("redis", check(() -> "PONG".equalsIgnoreCase(redis.execute((RedisCallback<String>) RedisConnection::ping))));
        c.put("collector", check(() -> redis.hasKey("rr:collector:heartbeat")));
        String status = c.get("db").equals("UP") ? (c.containsValue("DOWN") ? "DEGRADED" : "UP") : "DOWN";
        return new OpsDtos.Health(status, c, Times.now());
    }

    private static String check(java.util.function.BooleanSupplier s) {
        try { return s.getAsBoolean() ? "UP" : "DOWN"; } catch (RuntimeException e) { return "DOWN"; }
    }
}
