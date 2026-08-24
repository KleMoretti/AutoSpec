package com.autospec.controller;

import com.autospec.dto.ApiErrorResponse;
import com.autospec.exception.OptimisticLockConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OptimisticLockConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLockConflict(
            OptimisticLockConflictException ex,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(
                        "OPTIMISTIC_LOCK_CONFLICT",
                        HttpStatus.CONFLICT.value(),
                        ex.getMessage(),
                        request.getRequestURI(),
                        ex.getDetails()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity
                .badRequest()
                .body(ApiErrorResponse.validation(request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolvedStatus = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        return ResponseEntity
                .status(resolvedStatus)
                .headers(ex.getHeaders())
                .body(ApiErrorResponse.of(
                        errorCode(resolvedStatus),
                        resolvedStatus.value(),
                        ex.getReason() == null ? resolvedStatus.getReasonPhrase() : ex.getReason(),
                        request.getRequestURI()
                ));
    }

    private String errorCode(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            case BAD_GATEWAY -> "BAD_GATEWAY";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> "INTERNAL_ERROR";
        };
    }
}
