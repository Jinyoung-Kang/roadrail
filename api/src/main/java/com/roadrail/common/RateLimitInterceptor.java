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
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 클라이언트 IP 별 분당 요청 한도 (Redis 고정 창, INCR + EXPIRE 를 Lua 한 번으로 원자적으로).
 * 외부 API 예산을 쓰는 엔드포인트(카카오 · TAGO)가 한 사람의 반복 요청으로 하루 예산을 다 쓰지 않게,
 * 관리 API 는 토큰 추측을 막으려고. 한도는 roadrail.rate-limit (분당, 0 이면 끔).
 *
 * 버킷은 Spring 이 고른 **경로 패턴**으로 정한다 — 원본 URI 로 고르면 같은 핸들러로 가는 `/api/v1/%74rip` ·
 * `/api/v1;x=1/trip` 이 한도 밖이었다(RVW-04).
 * 클라이언트 IP: 요청이 믿는 프록시(루프백 · roadrail.trusted-proxies — 기본 compose 의 web)에서 왔으면
 * X-Forwarded-For 의 **맨 오른쪽** 값(그 프록시가 직접 본 주소)을 쓴다. 클라이언트가 넣은 왼쪽 값은 믿지 않는다.
 * 사설 대역 전체를 믿으면 Docker 게이트웨이(사설 주소)로 들어온 직접 요청이 머리글로 버킷을 바꿀 수 있었다.
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

    private static final long RESOLVE_EVERY_MS = 60_000;

    private final StringRedisTemplate redis;
    private final AppProperties props;
    private volatile long lastWarn = 0;
    private volatile Set<InetAddress> trusted = Set.of();
    private volatile long resolvedAt = 0;

    public RateLimitInterceptor(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String bucket = bucket(matchedPath(req));
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

    /**
     * 핸들러 매핑이 고른 패턴(디코딩 · 매트릭스 변수 제거 뒤). 컨트롤러가 없는 경로는 정적 자원 처리기가 '/**' 로 받으므로
     * 디코딩한 실제 경로로 — 모르는 관리 경로도 관리 한도에서 센다(토큰 검사는 경로 기준이라 401/404 로 대입 창구가 된다)
     */
    static String matchedPath(HttpServletRequest req) {
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String p = pattern == null ? null : pattern.toString();
        return p != null && p.startsWith("/api/") ? p : UrlPathHelper.defaultInstance.getLookupPathForRequest(req);
    }

    static String bucket(String path) {
        for (var b : BUCKETS) if (path.startsWith(b.getKey())) return b.getValue();
        return null;
    }

    String clientIp(HttpServletRequest req) {
        return clientIp(req, trustedProxies());
    }

    static String clientIp(HttpServletRequest req, Set<InetAddress> trusted) {
        String remote = req.getRemoteAddr();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank() && trustedProxy(remote, trusted)) {
            String[] hops = xff.split(",");
            return hops[hops.length - 1].trim();
        }
        return remote;
    }

    /** 루프백과 설정한 프록시 주소만 믿는다. IP 문자열만 받는다 — getByName 은 IP 리터럴이면 DNS 를 조회하지 않는다 */
    static boolean trustedProxy(String addr, Set<InetAddress> trusted) {
        if (addr == null || !addr.matches("[0-9a-fA-F:.]+")) return false;
        try {
            InetAddress a = InetAddress.getByName(addr);
            return a.isLoopbackAddress() || trusted.contains(a);
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    /** roadrail.trusted-proxies(이름 또는 IP)를 주소로 — 컨테이너를 다시 띄우면 주소가 바뀌므로 1분마다 다시 푼다 */
    private Set<InetAddress> trustedProxies() {
        long now = System.currentTimeMillis();
        if (now - resolvedAt < RESOLVE_EVERY_MS) return trusted;
        Set<InetAddress> out = new HashSet<>();
        for (String host : props.trustedProxies() == null ? List.<String>of() : props.trustedProxies()) {
            try {
                out.addAll(List.of(InetAddress.getAllByName(host.trim())));
            } catch (UnknownHostException | RuntimeException e) {
                // 이름을 못 풀면 그 프록시는 믿지 않는다(머리글을 무시하고 접속 주소로 센다)
            }
        }
        trusted = Set.copyOf(out);
        resolvedAt = now;
        return trusted;
    }
}
