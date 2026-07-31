package com.autospec.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class RetryAfterResponseStatusException extends ResponseStatusException {
    private static final long serialVersionUID = 1L;

    private final HttpHeaders headers = new HttpHeaders();

    public RetryAfterResponseStatusException(
            HttpStatus status,
            String reason,
            long retryAfterSeconds
    ) {
        this(status, reason, retryAfterSeconds, null);
    }

    public RetryAfterResponseStatusException(
            HttpStatus status,
            String reason,
            long retryAfterSeconds,
            Throwable cause
    ) {
        super(status, reason, cause);
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("Retry-After must be positive");
        }
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    }

    @Override
    public HttpHeaders getHeaders() {
        return HttpHeaders.readOnlyHttpHeaders(headers);
    }
}
