package com.autospec.workflow.runtime;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

public class RetryPolicyEvaluator {

    public Decision evaluate(
            int attempt,
            String errorCode,
            LocalDateTime failedAt,
            Policy policy
    ) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (failedAt == null) {
            throw new IllegalArgumentException("failedAt is required");
        }

        boolean retryable = policy.retryableErrors().contains(errorCode);
        if (retryable && attempt < policy.maxAttempts()) {
            Duration delay = backoff(attempt, policy);
            return new Decision(Action.RETRY, failedAt.plus(delay), null);
        }
        if (policy.fallbackHandler() != null && !policy.fallbackHandler().isBlank()) {
            return new Decision(Action.FALLBACK, failedAt, policy.fallbackHandler());
        }
        return new Decision(Action.FAIL, null, null);
    }

    private Duration backoff(int attempt, Policy policy) {
        double calculated = policy.initialDelay().toMillis()
                * Math.pow(policy.multiplier(), attempt - 1L);
        long cappedMillis = calculated >= policy.maxDelay().toMillis()
                ? policy.maxDelay().toMillis()
                : (long) calculated;
        return Duration.ofMillis(cappedMillis);
    }

    public enum Action {
        RETRY,
        FALLBACK,
        FAIL
    }

    public record Decision(Action action, LocalDateTime nextRetryAt, String handlerKey) {
    }

    public record Policy(
            int maxAttempts,
            Set<String> retryableErrors,
            Duration initialDelay,
            Duration maxDelay,
            double multiplier,
            String fallbackHandler
    ) {
        public Policy {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
            retryableErrors = retryableErrors == null ? Set.of() : Set.copyOf(retryableErrors);
            if (initialDelay == null || initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must not be negative");
            }
            if (maxDelay == null || maxDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException("maxDelay must be at least initialDelay");
            }
            if (!Double.isFinite(multiplier) || multiplier < 1.0) {
                throw new IllegalArgumentException("multiplier must be finite and at least 1.0");
            }
        }
    }
}
