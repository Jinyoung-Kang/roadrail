package com.roadrail.common;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 메모리 캐시 — 키마다 한 번만 불러오고(동시 요청은 같은 결과를 기다림), 실패는 캐시하지 않는다.
 * 불러오기(DB 조회 등)는 **맵 잠금 밖에서** 한다: ConcurrentHashMap.computeIfAbsent 는 synchronized 로 칸을 잠그므로,
 * 그 안에서 I/O 를 하면 JDK 21 가상 스레드가 캐리어 스레드를 고정(pinning)한다(재현: -Djdk.tracePinnedThreads →
 * reason:MONITOR · computeIfAbsent). 기다림은 CompletableFuture.join 이라 잠금 없이 파킹된다.
 * 크기가 maxSize 에 닿으면 통째로 비운다 — 기준 운행일 · 역 좌표처럼 키가 천천히 바뀌는 곳에 쓴다.
 */
public final class Memo<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> map = new ConcurrentHashMap<>();
    private final int maxSize;

    public Memo(int maxSize) {
        this.maxSize = maxSize;
    }

    public V get(K key, Function<K, V> loader) {
        CompletableFuture<V> f = map.get(key);
        if (f == null) {
            if (map.size() >= maxSize) map.clear();
            CompletableFuture<V> mine = new CompletableFuture<>();
            f = map.putIfAbsent(key, mine);
            if (f == null) {
                try {
                    V v = loader.apply(key);
                    mine.complete(v);
                    return v;
                } catch (Throwable t) {  // Error 까지 — 완료하지 않은 Future 가 남으면 그 키의 모든 호출이 join 에서 영원히 멈춘다
                    map.remove(key, mine);
                    mine.completeExceptionally(t);
                    throw t;
                }
            }
        }
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    public int size() {
        return map.size();
    }
}
