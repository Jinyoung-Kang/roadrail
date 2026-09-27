package com.roadrail.common;

/**
 * 지금 처리 중인 요청의 클라이언트(접속 주소) — 외부 API 예산을 클라이언트별 몫으로 나눌 때 쓴다 (QuotaGuard).
 * RateLimitInterceptor 가 요청 시작에 넣고 끝에 지운다. InheritableThreadLocal 이라 요청이 띄운 가상 스레드
 * (카카오 · TAGO 조회 등)는 만들어질 때 값을 물려받는다 — 요청이 끝난 뒤 계속되는 뒤쪽 작업도 그 클라이언트 몫.
 * 요청 밖(기동 · 스케줄)에서는 비어 있고, 그때는 몫을 적용하지 않는다.
 */
public final class ClientContext {
    private static final InheritableThreadLocal<String> CLIENT = new InheritableThreadLocal<>();

    private ClientContext() {}

    public static String current() {
        return CLIENT.get();
    }

    static void set(String client) {
        CLIENT.set(client);
    }

    static void clear() {
        CLIENT.remove();
    }

    /** client 로 r 을 실행하고 이전 값으로 되돌린다 (테스트 · 요청 밖 작업용) */
    public static void run(String client, Runnable r) {
        String prev = CLIENT.get();
        CLIENT.set(client);
        try {
            r.run();
        } finally {
            if (prev == null) CLIENT.remove();
            else CLIENT.set(prev);
        }
    }
}
