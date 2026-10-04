package com.roadrail.shared;

public record ErrorResponse(String code, String message, String traceId) {}
