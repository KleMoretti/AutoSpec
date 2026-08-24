package com.autospec.workflow.transport;

import com.autospec.dto.WorkflowOutboxBacklogSnapshot;
import com.autospec.mapper.WorkflowOutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowBacklogMetricsTest {
    private static final String STREAM = "autospec.workflow.events";
    private static final String GROUP = "autospec-control-plane";

    @Test
    void refreshesBacklogGaugesAndPreservesLastValuesOnCollectionFailure() {
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations =
                mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        WorkflowOutboxBacklogSnapshot snapshot = new WorkflowOutboxBacklogSnapshot();
        snapshot.setPendingCount(3L);
        snapshot.setOldestCreatedAt(LocalDateTime.of(2026, 7, 31, 11, 59, 30));
        when(mapper.selectPendingBacklog()).thenReturn(snapshot);
        when(streamOperations.pending(STREAM, GROUP)).thenReturn(
                new PendingMessagesSummary(
                        GROUP,
                        2,
                        Range.closed("1-0", "2-0"),
                        Map.of("worker-1", 2L)
                )
        );
        PendingMessage oldest = new PendingMessage(
                RecordId.of("1-0"),
                Consumer.from(GROUP, "worker-1"),
                Duration.ofSeconds(45),
                1
        );
        when(streamOperations.pending(eq(STREAM), eq(GROUP), any(Range.class), eq(1L)))
                .thenReturn(new PendingMessages(GROUP, List.of(oldest)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowBacklogMetrics metrics = new WorkflowBacklogMetrics(
                mapper,
                redisTemplate,
                registry,
                STREAM,
                GROUP,
                Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)
        );

        metrics.refresh();

        assertThat(gauge(registry, WorkflowBacklogMetrics.OUTBOX_PENDING)).isEqualTo(3);
        assertThat(gauge(registry, WorkflowBacklogMetrics.OUTBOX_OLDEST_AGE_SECONDS))
                .isEqualTo(30);
        assertThat(taggedGauge(registry, WorkflowBacklogMetrics.REDIS_STREAM_PENDING))
                .isEqualTo(2);
        assertThat(taggedGauge(
                registry,
                WorkflowBacklogMetrics.REDIS_STREAM_OLDEST_IDLE_SECONDS
        )).isEqualTo(45);

        when(mapper.selectPendingBacklog()).thenThrow(new IllegalStateException("db down"));
        when(streamOperations.pending(STREAM, GROUP))
                .thenThrow(new IllegalStateException("redis down"));

        metrics.refresh();

        assertThat(gauge(registry, WorkflowBacklogMetrics.OUTBOX_PENDING)).isEqualTo(3);
        assertThat(taggedGauge(registry, WorkflowBacklogMetrics.REDIS_STREAM_PENDING))
                .isEqualTo(2);
        assertThat(registry.get(WorkflowBacklogMetrics.COLLECTION_FAILURES)
                .tag("source", "mysql").counter().count()).isEqualTo(1);
        assertThat(registry.get(WorkflowBacklogMetrics.COLLECTION_FAILURES)
                .tag("source", "redis").counter().count()).isEqualTo(1);
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private double taggedGauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name)
                .tags("stream", STREAM, "consumer_group", GROUP)
                .gauge()
                .value();
    }
}
