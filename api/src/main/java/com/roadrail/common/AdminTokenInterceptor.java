package com.roadrail.common;

import com.roadrail.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** /admin/* 는 X-Admin-Token 필수 (7-1). 상수 시간 비교. */
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-Admin-Token";
    private final AppProperties props;

    public AdminTokenInterceptor(AppProperties props) { this.props = props; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equals(req.getMethod())) return true;
        String expected = props.adminToken();
        if (expected == null || expected.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "서버에 ADMIN_TOKEN 이 설정되지 않았습니다.");
        }
        String given = req.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "관리 토큰(X-Admin-Token)이 올바르지 않습니다.");
        }
        return true;
    }
}
