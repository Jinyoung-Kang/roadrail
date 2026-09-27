package com.roadrail.common;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 같은 키의 동시 작업을 하나로 합친다 — 진행 중이면 그 Future 를 돌려주고, 끝나면(성공 · 실패 모두) 맵에서 지운다.
 * Future 를 먼저 맵에 넣은 뒤 **맵 잠금 밖에서** 작업을 시작한다. computeIfAbsent 안에서 비동기 작업과 제거 콜백을
 * 걸면, 작업이 등록 전에 끝날 때 콜백이 잠금 안에서 remove 를 불러 IllegalStateException("Recursive update") 이 나고
 * 실패한 Future 가 맵에 영구히 남는다(재현 · ConcurrencyToolsTest).
 */
public final class SingleFlight<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();

    public CompletableFuture<V> run(K key, Supplier<V> work, Executor exec) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inflight.putIfAbsent(key, mine);
        if (running != null) return running;
        try {
            exec.execute(() -> {
                try {
                    mine.complete(work.get());
                } catch (Throwable t) {
                    mine.completeExceptionally(t);
                } finally {
                    inflight.remove(key, mine);
                }
            });
        } catch (RuntimeException e) {  // 실행기가 닫힘(종료 중) 등 — 맵에 남기지 않는다
            inflight.remove(key, mine);
            mine.completeExceptionally(e);
        }
        return mine;
    }

    /** 같은 키의 작업이 아직 진행 중인지 (화면이 잠시 뒤 다시 부르도록 알려 줄 때) */
    public boolean running(K key) {
        return inflight.containsKey(key);
    }
}
