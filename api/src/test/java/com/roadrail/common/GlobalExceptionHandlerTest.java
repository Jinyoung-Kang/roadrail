package com.roadrail.common;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    @Test
    void poolExhaustionIsRetryable503NotServerError() {
        // 가상 스레드는 동시 요청 수에 상한이 없어 연결 풀(12)이 차면 연결 대기 시간 초과가 난다 — 500 이 아니라 503 + Retry-After (ARC-03)
        var e = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 3000ms"));
        var res = new GlobalExceptionHandler().unavailable(e);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo("UNAVAILABLE");
    }
}
