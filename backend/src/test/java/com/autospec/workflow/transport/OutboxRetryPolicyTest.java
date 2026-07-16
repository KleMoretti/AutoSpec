package com.autospec.workflow.transport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRetryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 16, 12, 0);

    @Test
    void appliesExponentialBackoffAndCapsTheDelay() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(8),
                0,
                () -> 0.5
        );

        assertThat(policy.nextRetryAt(1, NOW)).isEqualTo(NOW.plusSeconds(1));
        assertThat(policy.nextRetryAt(2, NOW)).isEqualTo(NOW.plusSeconds(2));
        assertThat(policy.nextRetryAt(4, NOW)).isEqualTo(NOW.plusSeconds(8));
        assertThat(policy.nextRetryAt(20, NOW)).isEqualTo(NOW.plusSeconds(8));
    }

    @Test
    void addsBoundedJitterAroundTheExponentialDelay() {
        OutboxRetryPolicy lowJitter = new OutboxRetryPolicy(
                Duration.ofSeconds(10),
                Duration.ofMinutes(1),
                0.2,
                () -> 0
        );
        OutboxRetryPolicy highJitter = new OutboxRetryPolicy(
                Duration.ofSeconds(10),
                Duration.ofMinutes(1),
                0.2,
                () -> 1
        );

        assertThat(lowJitter.nextRetryAt(1, NOW)).isEqualTo(NOW.plusSeconds(8));
        assertThat(highJitter.nextRetryAt(1, NOW)).isEqualTo(NOW.plusSeconds(12));
        assertThat(highJitter.nextRetryAt(10, NOW)).isEqualTo(NOW.plusMinutes(1));
    }
}
