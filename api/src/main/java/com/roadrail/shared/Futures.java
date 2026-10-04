package com.roadrail.shared;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 마감이 있는 대기 — 여러 조회가 하나의 마감을 나눠 쓴다(판단 카드 · 철도 여정 · 경로 분석) */
public final class Futures {
    private Futures() {}

    /** 마감까지 남은 시간(최소 1ms) */
    public static Duration left(long deadlineNanos) {
        return Duration.ofNanos(Math.max(deadlineNanos - System.nanoTime(), 1_000_000));
    }

    /** wait 안에 끝나면 값, 늦으면 null. 작업의 예외는 그대로 던진다 */
    public static <T> T join(CompletableFuture<T> f, Duration wait) {
        try {
            return f.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        }
    }
}
