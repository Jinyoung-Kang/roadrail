package com.roadrail.shared;

import com.roadrail.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Redis JSON 캐시 — 같은 키를 동시에 못 찾은 요청은 불러오기를 한 번만 (캐시 쇄도 방지 · PERF-04) */
class JsonCacheIT extends IntegrationTest {
    @Autowired
    JsonCache cache;

    public record Box(int n) {}

    @Test
    void concurrentMissesLoadOnce() throws Exception {
        // 철도 분석 화면은 요청 3개(열차 · 요일 · 시간대)를 동시에 보내 같은 전국 비교 값을 각각 계산했다
        var loads = new AtomicInteger();
        List<Future<JsonCache.Hit<Box>>> got = new ArrayList<>();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                got.add(exec.submit(() -> cache.get("test:stampede", Duration.ofMinutes(1), Box.class, () -> {
                    sleep(150);
                    return new Box(loads.incrementAndGet());
                })));
            }
            for (var f : got) assertThat(f.get(5, TimeUnit.SECONDS).value()).isEqualTo(new Box(1));
        }
        assertThat(loads.get()).isEqualTo(1);
        assertThat(cache.get("test:stampede", Duration.ofMinutes(1), Box.class, () -> new Box(-1)).cached()).isTrue();
    }

    @Test
    void loaderFailureReachesTheCallerAndIsNotCached() {
        assertThatThrownBy(() -> cache.get("test:fail", Duration.ofMinutes(1), Box.class, () -> { throw new IllegalStateException("DB 오류"); }))
                .isInstanceOf(IllegalStateException.class).hasMessage("DB 오류");
        assertThat(cache.get("test:fail", Duration.ofMinutes(1), Box.class, () -> new Box(2)).value()).isEqualTo(new Box(2));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
}
