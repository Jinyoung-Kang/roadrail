package com.roadrail.config;

import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 작업을 맡긴 스레드의 MDC(요청 traceId)를 작업 스레드로 옮기는 실행기 — 병렬 조회 · 외부 호출의 경고 로그를 요청과
 * 이어 볼 수 있게(RVW-10). 작업이 끝나면 작업 스레드의 MDC 를 원래대로 돌린다. 나머지는 감싼 실행기에 그대로 맡긴다.
 */
final class MdcExecutorService extends AbstractExecutorService {
    private final ExecutorService delegate;

    MdcExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        delegate.execute(() -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            set(context);
            try {
                task.run();
            } finally {
                set(previous);
            }
        });
    }

    private static void set(Map<String, String> context) {
        if (context == null) MDC.clear();
        else MDC.setContextMap(context);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
