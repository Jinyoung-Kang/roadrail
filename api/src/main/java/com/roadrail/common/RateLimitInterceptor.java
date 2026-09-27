package com.roadrail.common;

import com.roadrail.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * 클라이언트 IP 별 분당 요청 한도 (Redis 고정 창, INCR + EXPIRE 를 Lua 한 번으로 원자적으로).
 * 외부 API 예산을 쓰는 엔드포인트(카카오 · TAGO)가 한 사람의 반복 요청으로 하루 예산을 다 쓰지 않게,
 * 관리 API 는 토큰 추측을 막으려고. 한도는 roadrail.rate-limit (분당, 0 이면 끔).
 *
 * 클라이언트 IP: 요청이 믿을 수 있는 프록시(루프백 · 사설망 = 같은 compose 네트워크의 Next.js)에서 왔으면
 * X-Forwarded-For 의 **맨 오른쪽** 값(그 프록시가 직접 본 주소)을 쓴다. 클라이언트가 넣은 왼쪽 값은 믿지 않는다.
 * Redis 를 쓸 수 없으면 막지 않는다(fail-open) — 가용성이 우선, 외부 호출은 QuotaGuard 가 별도로 지킨다.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final RedisScript<Long> INCR = RedisScript.of("""
            local v = redis.call('INCR', KEYS[1])
            if v == 1 then redis.call('EXPIRE', KEYS[1], 70) end
            return v
            """, Long.class);

    /** 경로 접두사 → 한도 이름 (먼저 맞는 것) */
    static final List<Map.Entry<String, String>> BUCKETS = List.of(
            Map.entry("/api/v1/admin/", "admin"),
            Map.entry("/api/v1/trip", "trip"),
            Map.entry("/api/v1/road/route", "route"),
            Map.entry("/api/v1/places/search", "search"),
            Map.entry("/api/v1/rail/od/", "rail"));

    private final StringRedisTemplate redis;
    private final AppProperties props;
    private volatile long lastWarn = 0;

    public RateLimitInterceptor(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String bucket = bucket(req.getRequestURI());
        int limit = bucket == null || props.rateLimit() == null ? 0 : props.rateLimit().getOrDefault(bucket, 0);
        if (limit <= 0) return true;
        long minute = System.currentTimeMillis() / 60_000;
        Long n;
        try {
            n = redis.execute(INCR, List.of("rl:" + bucket + ":" + clientIp(req) + ":" + minute));
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            if (now - lastWarn > 60_000) {
                lastWarn = now;
                log.warn("요청 한도 확인 실패 — 막지 않고 통과: {}", e.getClass().getSimpleName());
            }
            return true;
        }
        long count = n == null ? 0 : n;
        res.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(limit - count, 0)));
        if (count > limit) {
            long retry = 60 - (System.currentTimeMillis() / 1000) % 60;
            res.setHeader("Retry-After", String.valueOf(retry));
            throw new ApiException(ErrorCode.RATE_LIMITED, "요청이 너무 많습니다. " + retry + "초 뒤 다시 시도하세요 (분당 " + limit + "회).");
        }
        return true;
    }

    static String bucket(String uri) {
        for (var b : BUCKETS) if (uri.startsWith(b.getKey())) return b.getValue();
        return null;
    }

    static String clientIp(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank() && trustedProxy(remote)) {
            String[] hops = xff.split(",");
            return hops[hops.length - 1].trim();
        }
        return remote;
    }

    /** 루프백 · 사설 대역(10/8 · 172.16/12 · 192.168/16 · fc00::/7)만 프록시로 믿는다 — 포트는 127.0.0.1 에만 열려 있다 */
    static boolean trustedProxy(String addr) {
        // IP 문자열만 받는다 — getByName 은 IP 리터럴이면 DNS 를 조회하지 않는다
        if (addr == null || !addr.matches("[0-9a-fA-F:.]+")) return false;
        try {
            InetAddress a = InetAddress.getByName(addr);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc);
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return false;
        }
    }
}
