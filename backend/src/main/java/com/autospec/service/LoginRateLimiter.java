package com.autospec.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class LoginRateLimiter {
    private static final String KEY_PREFIX = "autospec:auth:login:";
    private static final DefaultRedisScript<Long> ATTEMPT_SCRIPT = new DefaultRedisScript<>("""
            local attempts = redis.call('INCR', KEYS[1])
            if attempts == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if attempts > tonumber(ARGV[2]) then
                return redis.call('PTTL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final int maxAttempts;
    private final Duration window;
    private final Counter rateLimitedCounter;
    private final Counter unavailableCounter;

    @Autowired
    public LoginRateLimiter(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${autospec.auth.login-rate-limit.enabled:true}") boolean enabled,
            @Value("${autospec.auth.login-rate-limit.max-attempts:5}") int maxAttempts,
            @Value("${autospec.auth.login-rate-limit.window:5m}") Duration window
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Login max attempts must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Login rate-limit window must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.rateLimitedCounter = meterRegistry.counter(
                "autospec.auth.login.rejections",
                "reason",
                "rate_limit"
        );
        this.unavailableCounter = meterRegistry.counter(
                "autospec.auth.login.rejections",
                "reason",
                "store_unavailable"
        );
    }

    public void checkAndRecord(String username, String clientAddress) {
        if (!enabled) {
            return;
        }
        Long retryAfterMillis;
        try {
            retryAfterMillis = redisTemplate.execute(
                    ATTEMPT_SCRIPT,
                    List.of(key(username, clientAddress)),
                    Long.toString(window.toMillis()),
                    Integer.toString(maxAttempts)
            );
        } catch (DataAccessException exception) {
            unavailableCounter.increment();
            throw unavailable(exception);
        }
        if (retryAfterMillis != null && retryAfterMillis > 0) {
            rateLimitedCounter.increment();
            long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1_000);
            throw rateLimited(retryAfterSeconds);
        }
    }

    public void reset(String username, String clientAddress) {
        if (!enabled) {
            return;
        }
        try {
            redisTemplate.delete(key(username, clientAddress));
        } catch (DataAccessException exception) {
            unavailableCounter.increment();
            throw unavailable(exception);
        }
    }

    private String key(String username, String clientAddress) {
        String normalizedUsername = Objects.toString(username, "").strip().toLowerCase(Locale.ROOT);
        String normalizedAddress = Objects.toString(clientAddress, "unknown").strip();
        return KEY_PREFIX + sha256(normalizedUsername + "\n" + normalizedAddress);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ResponseStatusException rateLimited(long retryAfterSeconds) {
        return new RetryAfterResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many login attempts",
                retryAfterSeconds,
                null
        );
    }

    private ResponseStatusException unavailable(DataAccessException cause) {
        return new RetryAfterResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Login protection is temporarily unavailable",
                1,
                cause
        );
    }

    private static final class RetryAfterResponseStatusException extends ResponseStatusException {
        private final HttpHeaders headers = new HttpHeaders();

        private RetryAfterResponseStatusException(
                HttpStatus status,
                String reason,
                long retryAfterSeconds,
                Throwable cause
        ) {
            super(status, reason, cause);
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
