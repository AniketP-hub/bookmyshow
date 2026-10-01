package com.paytm.seats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> body(int status, String code, String msg, Map<String, Object> extra) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", code);
        b.put("message", msg);
        b.put("request_id", MDC.get("request_id"));
        b.putAll(extra);
        MDC.put("outcome", code);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String, Object>> domain(DomainException e) {
        return body(e.status(), e.code(), e.getMessage(), e.extra());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return body(400, "invalid_request", "malformed request", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> media(Exception e) {
        return body(415, "unsupported_media_type", "use application/json", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(Exception e) {
        return body(405, "method_not_allowed", "method not allowed", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(Exception e) {
        return body(404, "not_found", "not found", Map.of());
    }

    /** Pool wait timed out / DB unreachable: shed load with 503 + Retry-After rather than hang or a generic 500. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<Map<String, Object>> overloaded(Exception e) {
        log.error("database unavailable or pool saturated: {}", e.getMessage());
        Map<String, Object> b = body(503, "overloaded", "temporarily unavailable, retry", Map.of()).getBody();
        return ResponseEntity.status(503).header("Retry-After", "1").body(b);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("unhandled error", e);
        return body(500, "internal_error", "internal error", Map.of());
    }
}
