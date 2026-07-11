package com.autospec.dto;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        String code,
        int status,
        String message,
        String path,
        Instant timestamp,
        Map<String, String> fieldErrors
) {
    public static ApiErrorResponse of(String code, int status, String message, String path) {
        return new ApiErrorResponse(code, status, message, path, Instant.now(), Map.of());
    }

    public static ApiErrorResponse validation(String path, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(
                "VALIDATION_FAILED",
                400,
                "Request validation failed",
                path,
                Instant.now(),
                fieldErrors
        );
    }
}
