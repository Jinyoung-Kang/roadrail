package com.roadrail.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Redis JSON 캐시. Redis 가 없으면 캐시 없이 동작한다 (조회는 계속 가능). */
@Component
public class JsonCache {
    private static final Logger log = LoggerFactory.getLogger(JsonCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SingleFlight<String, Object> loading = new SingleFlight<>();
    private volatile long lastWarn;

    public JsonCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public record Hit<T>(T value, boolean cached) {}

    /**
     * 캐시에 있으면 그 값, 없으면 loader 로 불러와 저장. 같은 키를 동시에 못 찾은 요청은 불러오기를 한 번만 하고 결과를 나눠 쓴다
     * (캐시 쇄도 방지 — 예: 철도 분석 화면의 동시 요청 3개가 같은 전국 비교 값을 각각 계산하던 것). 첫 요청의 스레드에서 불러오고
     * 나머지는 Future 를 기다린다. 실패는 캐시하지 않고 기다리던 요청에도 같은 예외를 던진다.
     * loader 안에서 같은 키로 get 을 다시 부르면 안 된다(자기 자신을 기다림).
     */
    public <T> Hit<T> get(String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        try {
            String hit = redis.opsForValue().get(key);
            if (hit != null) return new Hit<>(mapper.readValue(hit, type), true);
        } catch (RuntimeException e) {
            warn(e);
            return new Hit<>(loader.get(), false);
        }
        try {
            Object value = loading.run(key, () -> {
                T v = loader.get();
                if (v != null) put(key, v, ttl);
                return v;
            }, Runnable::run).join();
            return new Hit<>(type.cast(value), false);
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
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
