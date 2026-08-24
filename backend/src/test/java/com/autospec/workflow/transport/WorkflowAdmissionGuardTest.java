package com.autospec.workflow.transport;

import com.autospec.dto.WorkflowOutboxBacklogSnapshot;
import com.autospec.mapper.WorkflowOutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowAdmissionGuardTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T12:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void rejectsNewRunsWhenOutboxCountOrAgeExceedsTheConfiguredBoundary() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size(WorkflowOutboxPublisher.COMMAND_STREAM)).thenReturn(0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowAdmissionGuard guard = new WorkflowAdmissionGuard(
                mapper,
                redisTemplate,
                registry,
                true,
                10,
                Duration.ofMinutes(5),
                100,
                Duration.ofMillis(2_500),
                CLOCK
        );
        WorkflowOutboxBacklogSnapshot snapshot = snapshot(10, "2026-07-31T11:59:00");
        when(mapper.selectPendingBacklog()).thenReturn(snapshot);

        assertRejected(guard, "pending outbox", "3");
        assertThat(rejections(registry, "pending_count")).isEqualTo(1);

        snapshot.setPendingCount(1L);
        snapshot.setOldestCreatedAt(LocalDateTime.parse("2026-07-31T11:55:00"));

        assertRejected(guard, "stale outbox", "3");
        assertThat(rejections(registry, "oldest_age")).isEqualTo(1);
    }

    private WorkflowOutboxBacklogSnapshot snapshot(long count, String oldestCreatedAt) {
        WorkflowOutboxBacklogSnapshot snapshot = new WorkflowOutboxBacklogSnapshot();
        snapshot.setPendingCount(count);
        snapshot.setOldestCreatedAt(LocalDateTime.parse(oldestCreatedAt));
        return snapshot;
    }

    private void assertRejected(
            WorkflowAdmissionGuard guard,
            String expectedMessage,
            String expectedRetryAfter
    ) {
        Throwable thrown = catchThrowable(guard::admit);
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException exception = (ResponseStatusException) thrown;
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exception.getReason()).contains(expectedMessage);
        assertThat(exception.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(expectedRetryAfter);
    }

    private double rejections(SimpleMeterRegistry registry, String reason) {
        return registry.get(WorkflowAdmissionGuard.REJECTIONS)
                .tag("reason", reason)
                .counter()
                .count();
    }
}
