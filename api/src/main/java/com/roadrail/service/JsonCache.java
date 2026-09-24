package com.roadrail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Supplier;

/** Redis JSON 캐시. Redis 가 없으면 캐시 없이 동작한다 (조회는 계속 가능). */
@Component
public class JsonCache {
    private static final Logger log = LoggerFactory.getLogger(JsonCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private volatile long lastWarn;

    public JsonCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public record Hit<T>(T value, boolean cached) {}

    public <T> Hit<T> get(String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        try {
            String hit = redis.opsForValue().get(key);
            if (hit != null) return new Hit<>(mapper.readValue(hit, type), true);
        } catch (RuntimeException e) {
            warn(e);
            return new Hit<>(loader.get(), false);
        }
        T value = loader.get();
        try {
            if (value != null) redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            warn(e);
        }
        return new Hit<>(value, false);
    }

    public <T> T peek(String key, Class<T> type) {
        try {
            String hit = redis.opsForValue().get(key);
            return hit == null ? null : mapper.readValue(hit, type);
        } catch (RuntimeException e) {
            warn(e);
            return null;
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    private void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) {
            lastWarn = now;
            log.warn("Redis 캐시를 쓸 수 없어 캐시 없이 조회합니다: {}", e.getMessage());
        }
    }
}
