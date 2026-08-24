package com.autospec.workflow.transport;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class WorkflowTransportMetrics {
    public static final String OUTBOX_PUBLISH_SUCCESSES =
            "autospec.workflow.outbox.publish.successes";
    public static final String OUTBOX_PUBLISH_FAILURES =
            "autospec.workflow.outbox.publish.failures";
    public static final String OUTBOX_RETRIES =
            "autospec.workflow.outbox.retries";
    public static final String OUTBOX_DEAD_LETTERS =
            "autospec.workflow.outbox.dead.letters";
    public static final String OUTBOX_PUBLISH_DURATION =
            "autospec.workflow.outbox.publish.duration";
    public static final String REDIS_STREAM_FRESH =
            "autospec.redis.stream.fresh";
    public static final String REDIS_STREAM_RECLAIMED =
            "autospec.redis.stream.reclaimed";
    public static final String REDIS_STREAM_ACKNOWLEDGED =
            "autospec.redis.stream.acknowledged";
    public static final String EVENT_HANDLER_FAILURES =
            "autospec.workflow.event.handler.failures";
    public static final String EVENT_DEAD_LETTERS =
            "autospec.workflow.event.dead.letters";

    private final Counter outboxPublishSuccesses;
    private final Counter outboxPublishFailures;
    private final Counter outboxRetries;
    private final Counter outboxDeadLetters;
    private final Timer outboxPublishDuration;
    private final Counter freshEvents;
    private final Counter reclaimedEvents;
    private final Counter acknowledgedEvents;
    private final Counter eventHandlerFailures;
    private final Counter eventDeadLetters;

    public WorkflowTransportMetrics(MeterRegistry registry) {
        this.outboxPublishSuccesses = counter(
                registry,
                OUTBOX_PUBLISH_SUCCESSES,
                "Workflow outbox commands successfully published to Redis"
        );
        this.outboxPublishFailures = counter(
                registry,
                OUTBOX_PUBLISH_FAILURES,
                "Workflow outbox command publication failures"
        );
        this.outboxRetries = counter(
                registry,
                OUTBOX_RETRIES,
                "Workflow outbox retries successfully scheduled"
        );
        this.outboxDeadLetters = counter(
                registry,
                OUTBOX_DEAD_LETTERS,
                "Workflow outbox commands moved to dead letter status"
        );
        this.outboxPublishDuration = Timer.builder(OUTBOX_PUBLISH_DURATION)
                .description("Time spent publishing a workflow outbox command to Redis")
                .register(registry);
        this.freshEvents = counter(
                registry,
                REDIS_STREAM_FRESH,
                "Fresh workflow events read from Redis Streams"
        );
        this.reclaimedEvents = counter(
                registry,
                REDIS_STREAM_RECLAIMED,
                "Stale workflow events reclaimed from Redis Streams"
        );
        this.acknowledgedEvents = counter(
                registry,
                REDIS_STREAM_ACKNOWLEDGED,
                "Workflow events acknowledged in Redis Streams"
        );
        this.eventHandlerFailures = counter(
                registry,
                EVENT_HANDLER_FAILURES,
                "Workflow event handler failures before acknowledgement"
        );
        this.eventDeadLetters = counter(
                registry,
                EVENT_DEAD_LETTERS,
                "Invalid inbound workflow events quarantined before acknowledgement"
        );
    }

    static WorkflowTransportMetrics isolated() {
        return new WorkflowTransportMetrics(new SimpleMeterRegistry());
    }

    void recordOutboxPublishSuccess() {
        outboxPublishSuccesses.increment();
    }

    void recordOutboxPublishFailure() {
        outboxPublishFailures.increment();
    }

    void recordOutboxRetry() {
        outboxRetries.increment();
    }

    void recordOutboxDeadLetter() {
        outboxDeadLetters.increment();
    }

    void recordOutboxPublishDuration(long durationNanos) {
        outboxPublishDuration.record(Math.max(0, durationNanos), TimeUnit.NANOSECONDS);
    }

    void recordFreshEvents(int count) {
        increment(freshEvents, count);
    }

    void recordReclaimedEvents(int count) {
        increment(reclaimedEvents, count);
    }

    void recordAcknowledgedEvent() {
        acknowledgedEvents.increment();
    }

    void recordEventHandlerFailure() {
        eventHandlerFailures.increment();
    }

    void recordEventDeadLetter() {
        eventDeadLetters.increment();
    }

    private Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(registry);
    }

    private void increment(Counter counter, int count) {
        if (count > 0) {
            counter.increment(count);
        }
    }
}
