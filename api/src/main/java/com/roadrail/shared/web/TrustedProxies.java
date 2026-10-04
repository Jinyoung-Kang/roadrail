package com.roadrail.shared.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 믿는 프록시(roadrail.trusted-proxies — 이름 또는 IP)의 주소. 컨테이너를 다시 띄우면 주소가 바뀌므로 1분마다 다시 푼다.
 * 그 사이 X-Forwarded-For 를 붙인 사설 주소가 목록에 없으면(프록시가 새 주소로 다시 떴을 수 있다) 5초에 한 번까지 바로 다시 푼다 —
 * 예전에는 최대 1분 동안 모든 사용자가 프록시 주소 하나의 요청 한도로 묶였다.
 */
final class TrustedProxies {
    static final long RESOLVE_EVERY_MS = 60_000;
    static final long MISS_RETRY_MS = 5_000;

    private final Supplier<List<String>> hosts;
    private final Function<String, List<InetAddress>> resolver;
    private final LongSupplier clock;
    private volatile Set<InetAddress> trusted = Set.of();
    private volatile long resolvedAt = Long.MIN_VALUE / 2;

    TrustedProxies(Supplier<List<String>> hosts, Function<String, List<InetAddress>> resolver, LongSupplier clock) {
        this.hosts = hosts;
        this.resolver = resolver;
        this.clock = clock;
    }

    static TrustedProxies dns(Supplier<List<String>> hosts) {
        return new TrustedProxies(hosts, h -> {
            try {
                return List.of(InetAddress.getAllByName(h));
            } catch (UnknownHostException e) {
                return List.of();  // 이름을 못 풀면 그 프록시는 믿지 않는다(머리글을 무시하고 접속 주소로 센다)
            }
        }, System::currentTimeMillis);
    }

    /** 이 접속 주소를 믿나 — 루프백 또는 설정한 프록시 */
    boolean trusts(String remote) {
        InetAddress a = literal(remote);
        if (a == null) return false;
        if (a.isLoopbackAddress() || current().contains(a)) return true;
        // 사설 주소인데 목록에 없다 — 프록시가 새 주소로 다시 떴을 수 있으니 짧은 간격으로 다시 확인
        if (a.isSiteLocalAddress() && clock.getAsLong() - resolvedAt >= MISS_RETRY_MS) return resolve().contains(a);
        return false;
    }

    Set<InetAddress> current() {
        return clock.getAsLong() - resolvedAt < RESOLVE_EVERY_MS ? trusted : resolve();
    }

    private synchronized Set<InetAddress> resolve() {
        Set<InetAddress> out = new HashSet<>();
        for (String host : hosts.get() == null ? List.<String>of() : hosts.get()) {
            try {
                out.addAll(resolver.apply(host.trim()));
            } catch (RuntimeException e) {
                // 못 풀면 그 프록시는 믿지 않는다
            }
        }
        trusted = Set.copyOf(out);
        resolvedAt = clock.getAsLong();
        return trusted;
    }

    /** IP 문자열만 받는다 — getByName 은 IP 리터럴이면 DNS 를 조회하지 않는다 */
    static InetAddress literal(String addr) {
        if (addr == null || !addr.matches("[0-9a-fA-F:.]+")) return null;
        try {
            return InetAddress.getByName(addr);
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }
}
