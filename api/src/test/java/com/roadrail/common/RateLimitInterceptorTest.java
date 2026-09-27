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
    void trustsOnlyTheRightmostHopFromLocalProxy() {
        // Next.js(같은 compose 네트워크)가 붙인 맨 오른쪽 값 = 실제 접속 주소. 클라이언트가 넣은 왼쪽 값은 무시
        assertThat(RateLimitInterceptor.clientIp(req("172.18.0.5", "1.2.3.4, 203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(RateLimitInterceptor.clientIp(req("127.0.0.1", "198.51.100.7"))).isEqualTo("198.51.100.7");
        // 믿을 수 없는(공인) 주소에서 온 X-Forwarded-For 는 무시 → 위조로 한도를 피할 수 없다
        assertThat(RateLimitInterceptor.clientIp(req("203.0.113.9", "10.0.0.1"))).isEqualTo("203.0.113.9");
        assertThat(RateLimitInterceptor.clientIp(req("172.18.0.5", null))).isEqualTo("172.18.0.5");
    }

    @Test
    void trustedProxyRanges() {
        assertThat(RateLimitInterceptor.trustedProxy("10.1.2.3")).isTrue();
        assertThat(RateLimitInterceptor.trustedProxy("192.168.0.1")).isTrue();
        assertThat(RateLimitInterceptor.trustedProxy("::1")).isTrue();
        assertThat(RateLimitInterceptor.trustedProxy("8.8.8.8")).isFalse();
        assertThat(RateLimitInterceptor.trustedProxy("evil.example.com")).isFalse();  // 이름은 조회하지 않고 거부
    }
}
