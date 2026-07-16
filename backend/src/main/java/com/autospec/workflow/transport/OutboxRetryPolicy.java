package com.autospec.workflow.transport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Component
public class OutboxRetryPolicy {
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double jitterRatio;
    private final DoubleSupplier random;

    public OutboxRetryPolicy(
            @Value("${autospec.workflow.outbox.retry.base-delay:1s}") Duration baseDelay,
            @Value("${autospec.workflow.outbox.retry.max-delay:1m}") Duration maxDelay,
            @Value("${autospec.workflow.outbox.retry.jitter-ratio:0.2}") double jitterRatio
    ) {
        this(baseDelay, maxDelay, jitterRatio, () -> ThreadLocalRandom.current().nextDouble());
    }

    OutboxRetryPolicy(
            Duration baseDelay,
            Duration maxDelay,
            double jitterRatio,
            DoubleSupplier random
    ) {
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be less than baseDelay");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
        }
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.jitterRatio = jitterRatio;
        this.random = random;
    }

    public LocalDateTime nextRetryAt(int retryCount, LocalDateTime now) {
        int safeRetryCount = Math.max(1, retryCount);
        int exponent = Math.min(safeRetryCount - 1, 30);
        long multiplier = 1L << exponent;
        long exponentialMillis;
        try {
            exponentialMillis = Math.multiplyExact(baseDelay.toMillis(), multiplier);
        } catch (ArithmeticException exception) {
            exponentialMillis = Long.MAX_VALUE;
        }
        long boundedMillis = Math.min(exponentialMillis, maxDelay.toMillis());
        double jitterMultiplier = 1 + ((random.getAsDouble() * 2) - 1) * jitterRatio;
        long jitteredMillis = Math.max(1, Math.round(boundedMillis * jitterMultiplier));
        long delayMillis = Math.min(jitteredMillis, maxDelay.toMillis());
        return now.plus(Duration.ofMillis(delayMillis));
    }
}
