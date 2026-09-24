package com.roadrail.common;

import org.springframework.http.HttpStatus;

/** 오류 규약 (7-1): {code, message, traceId} */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    CORRIDOR_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    JOB_RUNNING(HttpStatus.CONFLICT),
    QUOTA_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS),
    STALE_DATA(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) { this.status = status; }

    public HttpStatus status() { return status; }
}
