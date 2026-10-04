package com.roadrail.shared.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedProxiesTest {
    static InetAddress ip(String s) {
        try { return InetAddress.getByName(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void proxyThatCameBackOnANewAddressIsTrustedWithinSecondsNotAMinute() {
        // 남은 위험(검토): web 컨테이너가 새 주소로 다시 뜨면 최대 1분 동안 머리글을 무시해 모든 사용자가 한 버킷(분당 40)으로 묶였다
        AtomicReference<String> web = new AtomicReference<>("172.18.0.5");
        AtomicLong now = new AtomicLong(0);
        AtomicInteger lookups = new AtomicInteger();
        var p = new TrustedProxies(() -> List.of("web"), h -> { lookups.incrementAndGet(); return List.of(ip(web.get())); }, now::get);
        assertThat(p.trusts("172.18.0.5")).isTrue();
        web.set("172.18.0.9");                       // 재기동 — 새 주소
        now.set(10_000);
        assertThat(p.trusts("172.18.0.9")).isTrue(); // 1분을 기다리지 않는다
        assertThat(p.trusts("172.18.0.5")).isFalse();
        assertThat(lookups.get()).isEqualTo(2);
    }

    @Test
    void untrustedClientsCannotForceLookupsMoreThanEveryFiveSeconds() {
        AtomicLong now = new AtomicLong(0);
        AtomicInteger lookups = new AtomicInteger();
        var p = new TrustedProxies(() -> List.of("web"), h -> { lookups.incrementAndGet(); return List.of(ip("172.18.0.5")); }, now::get);
        p.trusts("172.18.0.5");
        for (int i = 0; i < 100; i++) {
            now.addAndGet(100);                      // 10초 동안 100번
            assertThat(p.trusts("172.18.0.1")).isFalse();   // Docker 게이트웨이로 들어온 직접 요청 — 믿지 않는다
        }
        assertThat(lookups.get()).isLessThanOrEqualTo(3);   // 처음 1 + 5초마다 1
        assertThat(p.trusts("203.0.113.9")).isFalse();      // 공인 주소는 다시 풀지 않는다
        assertThat(p.trusts("::1")).isTrue();
        assertThat(p.trusts("web")).isFalse();              // 이름은 받지 않는다(DNS 조회 없음)
    }
}
