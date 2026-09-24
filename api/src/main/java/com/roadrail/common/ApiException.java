package com.roadrail.common;

public class ApiException extends RuntimeException {
    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }

    public static ApiException corridorNotFound(String id) {
        return new ApiException(ErrorCode.CORRIDOR_NOT_FOUND, "길 '" + id + "' 를 찾을 수 없습니다.");
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }
}
