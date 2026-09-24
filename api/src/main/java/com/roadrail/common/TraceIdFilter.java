package com.roadrail.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;

/** 요청마다 traceId(ULID 형식 26자)를 만들어 로그 MDC · 응답 헤더 · 오류 본문에 쓴다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String MDC_KEY = "traceId";
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = ulid();
        MDC.put(MDC_KEY, id);
        res.setHeader("X-Trace-Id", id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String ulid() {
        long time = System.currentTimeMillis();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (time & 31)];
            time >>>= 5;
        }
        for (int i = 10; i < 26; i++) out[i] = CROCKFORD[RND.nextInt(32)];
        return new String(out);
    }
}
