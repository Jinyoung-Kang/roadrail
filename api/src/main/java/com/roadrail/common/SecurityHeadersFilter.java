package com.roadrail.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 모든 API 응답의 기본 보안 헤더 — 특히 API 문서(Swagger UI)를 다른 사이트가 프레임으로 넣지 못하게(클릭재킹, WEB-10).
 * 웹(Next)은 /docs · /swagger-ui · /v3 를 외부 rewrite 로 넘기는데 거기에는 Next 의 headers() 가 붙지 않는다.
 * CSP 는 frame-ancestors 만 — Swagger UI 의 인라인 스크립트는 막지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        res.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(req, res);
    }
}
