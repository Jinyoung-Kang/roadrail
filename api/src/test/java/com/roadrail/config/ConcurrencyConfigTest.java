package com.roadrail.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyConfigTest {
    @Test
    void shutdownWaitsOnlyUpToTheGraceThenInterruptsLeftoverWork() throws Exception {
        // close() 는 기다림에 상한이 없다 — 뒤에 쌓인 시간표 받기가 compose 유예(10초)를 넘기면 강제 종료(SIGKILL)된다 (셀프 리뷰)
        var exec = Executors.newVirtualThreadPerTaskExecutor();
        var interrupted = new CountDownLatch(1);
        exec.submit(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        long t0 = System.nanoTime();
        ConcurrencyConfig.shutdown(exec, Duration.ofMillis(200));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(exec.isShutdown()).isTrue();
    }

    @Test
    void shutdownLetsShortWorkFinish() throws Exception {
        var exec = Executors.newVirtualThreadPerTaskExecutor();
        var done = new CountDownLatch(1);
        exec.submit(() -> {
            try {
                Thread.sleep(50);
                done.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ConcurrencyConfig.shutdown(exec, Duration.ofSeconds(2));
        assertThat(done.getCount()).isZero();
        assertThat(exec.isTerminated()).isTrue();
    }
}
