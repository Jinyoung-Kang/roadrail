package com.roadrail.shared.web;

import com.roadrail.shared.ApiException;
import com.roadrail.shared.ErrorCode;
import com.roadrail.shared.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private volatile long lastUnavailableWarn = 0;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        return of(e.code(), e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> constraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream().map(v -> {
            String path = v.getPropertyPath().toString();
            return path.substring(path.lastIndexOf('.') + 1) + ": " + v.getMessage();
        }).sorted().collect(Collectors.joining(", "));
        return of(ErrorCode.VALIDATION_ERROR, msg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> methodValidation(HandlerMethodValidationException e) {
        String msg = e.getParameterValidationResults().stream()
                .map(r -> r.getMethodParameter().getParameterName() + ": "
                        + r.getResolvableErrors().stream().map(er -> er.getDefaultMessage()).collect(Collectors.joining(" ")))
                .sorted().collect(Collectors.joining(", "));
        return of(ErrorCode.VALIDATION_ERROR, msg);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return of(ErrorCode.VALIDATION_ERROR, "파라미터 '" + e.getName() + "' 의 형식이 올바르지 않습니다: " + e.getValue());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> missingParam(MissingServletRequestParameterException e) {
        return of(ErrorCode.VALIDATION_ERROR, "필수 파라미터 '" + e.getParameterName() + "' 가 없습니다.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return of(ErrorCode.VALIDATION_ERROR, "요청 본문(JSON)을 읽을 수 없습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return of(ErrorCode.METHOD_NOT_ALLOWED, e.getMethod() + " 은(는) 이 경로에서 지원하지 않습니다.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return of(ErrorCode.NOT_FOUND, "경로를 찾을 수 없습니다.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "요청 본문 형식(Content-Type)이 올바르지 않습니다 — application/json 을 보내세요.");
    }

    /** 클라이언트가 JSON 을 받지 않는다 — 오류 본문도 쓸 수 없으므로 상태만 (본문을 쓰려 하면 처리기 안에서 다시 실패한다) */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> notAcceptable(HttpMediaTypeNotAcceptableException e) {
        return ResponseEntity.status(ErrorCode.NOT_ACCEPTABLE.status()).build();
    }

    /**
     * 연결 풀이 가득 참(과부하) · DB 연결 불가 — 서버 오류(500)가 아니라 잠시 뒤 다시 시도할 일 (ARC-03).
     * 가상 스레드는 동시 요청 수에 상한이 없어 풀(12)이 차면 대기가 생긴다 → 대기는 짧게(hikari connection-timeout 3초) 끊고 503.
     */
    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    ResponseEntity<ErrorResponse> unavailable(CannotGetJdbcConnectionException e) {
        long now = System.currentTimeMillis();
        if (now - lastUnavailableWarn > 10_000) {
            lastUnavailableWarn = now;
            log.warn("DB 연결을 얻지 못해 503 으로 응답: {}", e.getMostSpecificCause().getMessage());
        }
        return ResponseEntity.status(ErrorCode.UNAVAILABLE.status()).header("Retry-After", "5")
                .body(new ErrorResponse(ErrorCode.UNAVAILABLE.name(), "요청이 몰려 잠시 처리할 수 없습니다. 몇 초 뒤 다시 시도하세요.",
                        MDC.get(TraceIdFilter.MDC_KEY)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unknown(Exception e) {
        // 그 밖의 Spring MVC 표준 예외(ErrorResponse 구현)는 그 상태 코드를 그대로 — 클라이언트 오류를 500 · ERROR 로 남기지 않는다
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            log.debug("요청 오류 {}: {}", er.getStatusCode().value(), e.getMessage());
            ErrorCode code = ErrorCode.of(er.getStatusCode().value());
            return ResponseEntity.status(er.getStatusCode())
                    .body(new ErrorResponse(code.name(), "요청을 처리할 수 없습니다 (HTTP " + er.getStatusCode().value() + ").",
                            MDC.get(TraceIdFilter.MDC_KEY)));
        }
        log.error("처리되지 않은 오류", e);
        return of(ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다.");
    }

    private static ResponseEntity<ErrorResponse> of(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
                .body(new ErrorResponse(code.name(), message, MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
