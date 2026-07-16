package com.autospec.workflow.transport;

import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowTransportMetricsTest {

    @Test
    void recordsOutboxPublishOutcomesRetriesAndDurationWithoutBusinessTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowTransportMetrics metrics = new WorkflowTransportMetrics(registry);
        WorkflowOutboxMapper mapper = mock(WorkflowOutboxMapper.class);
        WorkflowOutbox failed = pendingOutbox(21L, "command-1");
        WorkflowOutbox successful = pendingOutbox(22L, "command-2");
        when(mapper.selectList(any())).thenReturn(List.of(failed, successful));
        when(mapper.update(any(), any())).thenReturn(1);
        AtomicInteger attempts = new AtomicInteger();
        WorkflowCommandPublisher commandPublisher = (stream, eventId, payloadJson) -> {
            LockSupport.parkNanos(100_000);
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("redis unavailable");
            }
        };
        WorkflowOutboxPublisher publisher = new WorkflowOutboxPublisher(
                mapper,
                commandPublisher,
                new OutboxRetryPolicy(
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        0,
                        () -> 0.5
                ),
                metrics
        );

        int published = publisher.publishPending(10);

        assertThat(published).isEqualTo(1);
        assertThat(counter(registry, WorkflowTransportMetrics.OUTBOX_PUBLISH_SUCCESSES))
                .isEqualTo(1);
        assertThat(counter(registry, WorkflowTransportMetrics.OUTBOX_PUBLISH_FAILURES))
                .isEqualTo(1);
        assertThat(counter(registry, WorkflowTransportMetrics.OUTBOX_RETRIES))
                .isEqualTo(1);
        assertThat(registry.get(WorkflowTransportMetrics.OUTBOX_PUBLISH_DURATION)
                .timer()
                .count()).isEqualTo(2);
        assertThat(registry.get(WorkflowTransportMetrics.OUTBOX_PUBLISH_DURATION)
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
        assertThat(registry.getMeters()).allSatisfy(
                meter -> assertThat(meter.getId().getTags()).isEmpty()
        );
    }

    @Test
    void recordsFreshReclaimedAcknowledgedAndHandlerFailureEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowTransportMetrics metrics = new WorkflowTransportMetrics(registry);
        FakeEventStreamClient successfulClient = new FakeEventStreamClient();
        successfulClient.reclaimedMessages = List.of(
                new WorkflowStreamEventMessage("1-0", "{\"event_id\":\"stale\"}")
        );
        successfulClient.freshMessages = List.of(
                new WorkflowStreamEventMessage("2-0", "{\"event_id\":\"fresh-1\"}"),
                new WorkflowStreamEventMessage("3-0", "{\"event_id\":\"fresh-2\"}")
        );
        WorkflowEventPoller successfulPoller = new WorkflowEventPoller(
                successfulClient,
                payload -> {
                },
                "control-1",
                10,
                Duration.ofSeconds(30),
                metrics
        );

        assertThat(successfulPoller.pollOnce()).isEqualTo(3);

        FakeEventStreamClient failingClient = new FakeEventStreamClient();
        failingClient.freshMessages = List.of(
                new WorkflowStreamEventMessage("4-0", "{\"event_id\":\"failing\"}")
        );
        WorkflowEventPoller failingPoller = new WorkflowEventPoller(
                failingClient,
                payload -> {
                    throw new IllegalStateException("database unavailable");
                },
                "control-2",
                10,
                Duration.ofSeconds(30),
                metrics
        );

        assertThatThrownBy(failingPoller::pollOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        assertThat(counter(registry, WorkflowTransportMetrics.REDIS_STREAM_RECLAIMED))
                .isEqualTo(1);
        assertThat(counter(registry, WorkflowTransportMetrics.REDIS_STREAM_FRESH))
                .isEqualTo(3);
        assertThat(counter(registry, WorkflowTransportMetrics.REDIS_STREAM_ACKNOWLEDGED))
                .isEqualTo(3);
        assertThat(counter(registry, WorkflowTransportMetrics.EVENT_HANDLER_FAILURES))
                .isEqualTo(1);
        assertThat(failingClient.acknowledged).isEmpty();
        assertThat(registry.getMeters()).allSatisfy(
                meter -> assertThat(meter.getId().getTags()).isEmpty()
        );
    }

    private double counter(SimpleMeterRegistry registry, String name) {
        return registry.get(name).counter().count();
    }

    private WorkflowOutbox pendingOutbox(long id, String eventId) {
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setId(id);
        outbox.setEventId(eventId);
        outbox.setAggregateId("7");
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson("{\"execution_id\":\"7:fixture:1:1\"}");
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(LocalDateTime.now());
        return outbox;
    }

    private static class FakeEventStreamClient implements WorkflowEventStreamClient {
        private List<WorkflowStreamEventMessage> reclaimedMessages = List.of();
        private List<WorkflowStreamEventMessage> freshMessages = List.of();
        private final List<String> acknowledged = new ArrayList<>();

        @Override
        public void ensureGroup(String stream, String group) {
        }

        @Override
        public List<WorkflowStreamEventMessage> claimStale(
                String stream,
                String group,
                String consumer,
                Duration minimumIdleTime,
                int count
        ) {
            return reclaimedMessages;
        }

        @Override
        public List<WorkflowStreamEventMessage> read(
                String stream,
                String group,
                String consumer,
                int count
        ) {
            return freshMessages;
        }

        @Override
        public void acknowledge(String stream, String group, String messageId) {
            acknowledged.add(messageId);
        }
    }
}
