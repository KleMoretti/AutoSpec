package com.autospec.workflow.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyEvaluatorTest {
    private final RetryPolicyEvaluator evaluator = new RetryPolicyEvaluator();
    private final LocalDateTime failedAt = LocalDateTime.of(2026, 7, 12, 12, 0);

    @Test
    void computesExponentialBackoffAndCapsIt() {
        RetryPolicyEvaluator.Policy policy = policy(5, "fallback-v1");

        assertThat(evaluator.evaluate(1, "MODEL_TIMEOUT", failedAt, policy).nextRetryAt())
                .isEqualTo(failedAt.plusSeconds(1));
        assertThat(evaluator.evaluate(2, "MODEL_TIMEOUT", failedAt, policy).nextRetryAt())
                .isEqualTo(failedAt.plusSeconds(2));
        assertThat(evaluator.evaluate(4, "MODEL_TIMEOUT", failedAt, policy).nextRetryAt())
                .isEqualTo(failedAt.plusSeconds(5));
    }

    @Test
    void doesNotRetryErrorsOutsideWhitelist() {
        RetryPolicyEvaluator.Decision decision = evaluator.evaluate(
                1, "INVALID_OUTPUT", failedAt, policy(3, null)
        );

        assertThat(decision.action()).isEqualTo(RetryPolicyEvaluator.Action.FAIL);
        assertThat(decision.nextRetryAt()).isNull();
    }

    @Test
    void selectsFallbackAfterPrimaryAttemptsAreExhausted() {
        RetryPolicyEvaluator.Decision decision = evaluator.evaluate(
                3, "MODEL_TIMEOUT", failedAt, policy(3, "fallback-v1")
        );

        assertThat(decision.action()).isEqualTo(RetryPolicyEvaluator.Action.FALLBACK);
        assertThat(decision.handlerKey()).isEqualTo("fallback-v1");
        assertThat(decision.nextRetryAt()).isEqualTo(failedAt);
    }

    @Test
    void failsWhenAttemptsAreExhaustedWithoutFallback() {
        RetryPolicyEvaluator.Decision decision = evaluator.evaluate(
                3, "MODEL_TIMEOUT", failedAt, policy(3, null)
        );

        assertThat(decision.action()).isEqualTo(RetryPolicyEvaluator.Action.FAIL);
    }

    private RetryPolicyEvaluator.Policy policy(int maxAttempts, String fallbackHandler) {
        return new RetryPolicyEvaluator.Policy(
                maxAttempts,
                Set.of("MODEL_TIMEOUT", "PROVIDER_UNAVAILABLE"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                2.0,
                fallbackHandler
        );
    }
}
