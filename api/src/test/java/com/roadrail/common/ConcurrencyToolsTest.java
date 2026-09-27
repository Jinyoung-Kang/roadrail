package com.roadrail.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SingleFlight(같은 작업 합치기) · Memo(메모리 캐시) — BUG-07 · ARC-02 */
class ConcurrencyToolsTest {

    // ------------------------------------------------------------------ SingleFlight

    @Test
    void singleFlightSurvivesWorkThatFinishesImmediately() {
        // 예전 패턴(computeIfAbsent 안에서 supplyAsync + whenComplete(remove))은 작업이 먼저 끝나면
        // IllegalStateException("Recursive update") 이 나고 실패한 Future 가 맵에 영구히 남았다 (BUG-07 재현 조건)
        var flight = new SingleFlight<String, String>();
        var f = flight.run("k", () -> "v", Runnable::run);
        assertThat(f.join()).isEqualTo("v");
        assertThat(flight.running("k")).isFalse();
    }

    @Test
    void concurrentCallersShareOneRun() throws Exception {
        var flight = new SingleFlight<String, Integer>();
        var runs = new AtomicInteger();
        var gate = new CountDownLatch(1);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = flight.run("k", () -> { await(gate); return runs.incrementAndGet(); }, exec);
            var b = flight.run("k", () -> runs.incrementAndGet(), exec);
            assertThat(b).isSameAs(a);
            assertThat(flight.running("k")).isTrue();
            gate.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(runs.get()).isEqualTo(1);
        assertThat(flight.running("k")).isFalse();
    }

    @Test
    void singleFlightFailureIsNotSticky() {
        var flight = new SingleFlight<String, String>();
        var failed = flight.run("k", () -> { throw new IllegalStateException("외부 오류"); }, Runnable::run);
        assertThat(failed).isCompletedExceptionally();
        assertThat(flight.running("k")).isFalse();                         // 실패가 맵에 남지 않는다
        assertThat(flight.run("k", () -> "다시", Runnable::run).join()).isEqualTo("다시");
    }

    // ------------------------------------------------------------------ Memo

    @Test
    void memoLoadsOncePerKeyUnderConcurrency() throws Exception {
        var memo = new Memo<String, Integer>(8);
        var loads = new AtomicInteger();
        List<Future<Integer>> got = new ArrayList<>();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 16; i++) {
                got.add(exec.submit(() -> memo.get("2026-09-22", k -> { sleep(50); return loads.incrementAndGet(); })));
            }
            for (var f : got) assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void memoDoesNotCacheFailuresAndRethrowsTheOriginalException() {
        var memo = new Memo<String, String>(8);
        assertThatThrownBy(() -> memo.get("k", k -> { throw new IllegalArgumentException("DB 오류"); }))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("DB 오류");
        assertThat(memo.get("k", k -> "값")).isEqualTo("값");
    }

    @Test
    void memoLoaderRunsOutsideTheMapLock() {
        // 로더가 같은 Memo 의 다른 키를 불러도 된다 — computeIfAbsent 안에서라면 맵 잠금을 쥔 채 도는 구조
        var memo = new Memo<String, String>(8);
        assertThat(memo.get("a", k -> "A+" + memo.get("b", kk -> "B"))).isEqualTo("A+B");
    }

    @Test
    void memoIsBounded() {
        var memo = new Memo<Integer, Integer>(3);
        for (int i = 0; i < 10; i++) memo.get(i, k -> k);
        assertThat(memo.size()).isLessThanOrEqualTo(3);
    }

    private static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }
}
