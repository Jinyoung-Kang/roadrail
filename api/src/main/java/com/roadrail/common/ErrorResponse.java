package com.roadrail.common;

public record ErrorResponse(String code, String message, String traceId) {}
