package com.roadrail.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitInterceptorTest {
    private static MockHttpServletRequest req(String remote, String xff) {
        var r = new MockHttpServletRequest("GET", "/api/v1/trip");
        r.setRemoteAddr(remote);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void bucketsByPathPrefix() {
        assertThat(RateLimitInterceptor.bucket("/api/v1/trip")).isEqualTo("trip");
        assertThat(RateLimitInterceptor.bucket("/api/v1/rail/od/punctuality")).isEqualTo("rail");
        assertThat(RateLimitInterceptor.bucket("/api/v1/admin/jobs/x/run")).isEqualTo("admin");
        assertThat(RateLimitInterceptor.bucket("/api/v1/corridors")).isNull();  // DB 조회만 하는 곳은 한도 없음
    }

    @Test
    void bucketComesFromTheMatchedHandlerPattern() {
        var r = new MockHttpServletRequest("GET", "/api/v1/%74rip");
        r.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/trip");
        assertThat(RateLimitInterceptor.bucket(RateLimitInterceptor.matchedPath(r))).isEqualTo("trip");
        assertThat(RateLimitInterceptor.matchedPath(new MockHttpServletRequest("GET", "/x"))).isEqualTo("/x");
    }

    @Test
    void trustsOnlyTheRightmostHopFromAConfiguredProxy() throws Exception {
        var web = java.util.Set.of(java.net.InetAddress.getByName("172.18.0.5"));
        // 설정한 프록시(Next.js)가 붙인 맨 오른쪽 값 = 실제 접속 주소. 클라이언트가 넣은 왼쪽 값은 무시
        assertThat(RateLimitInterceptor.clientIp(req("172.18.0.5", "1.2.3.4, 203.0.113.9"), web)).isEqualTo("203.0.113.9");
        assertThat(RateLimitInterceptor.clientIp(req("127.0.0.1", "198.51.100.7"), web)).isEqualTo("198.51.100.7");
        // 다른 사설 주소(예: Docker 게이트웨이로 들어온 직접 요청)의 머리글은 믿지 않는다 (RVW-04)
        assertThat(RateLimitInterceptor.clientIp(req("172.18.0.1", "10.0.0.1"), web)).isEqualTo("172.18.0.1");
        assertThat(RateLimitInterceptor.clientIp(req("203.0.113.9", "10.0.0.1"), web)).isEqualTo("203.0.113.9");
        assertThat(RateLimitInterceptor.clientIp(req("172.18.0.5", null), web)).isEqualTo("172.18.0.5");
    }

    @Test
    void trustedProxyIsLoopbackOrConfigured() throws Exception {
        var web = java.util.Set.of(java.net.InetAddress.getByName("10.1.2.3"));
        assertThat(RateLimitInterceptor.trustedProxy("10.1.2.3", web)).isTrue();
        assertThat(RateLimitInterceptor.trustedProxy("::1", web)).isTrue();
        assertThat(RateLimitInterceptor.trustedProxy("192.168.0.1", web)).isFalse();   // 사설 대역이라고 믿지 않는다
        assertThat(RateLimitInterceptor.trustedProxy("8.8.8.8", web)).isFalse();
        assertThat(RateLimitInterceptor.trustedProxy("evil.example.com", web)).isFalse();  // 이름은 조회하지 않고 거부
    }
}
