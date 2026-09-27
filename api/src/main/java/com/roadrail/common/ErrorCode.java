package com.roadrail.common;

import org.springframework.http.HttpStatus;

/** 오류 규약 (7-1): {code, message, traceId} */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    CORRIDOR_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    JOB_RUNNING(HttpStatus.CONFLICT),
    QUOTA_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    STALE_DATA(HttpStatus.SERVICE_UNAVAILABLE),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) { this.status = status; }

    public HttpStatus status() { return status; }

    /** 상태 코드 → 오류 코드 (Spring 표준 예외처럼 상태만 아는 경우) */
    public static ErrorCode of(int status) {
        return switch (status) {
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 503 -> UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : VALIDATION_ERROR;
        };
    }
}
