package com.autospec.workflow.transport;

import com.autospec.dto.WorkflowOutboxBacklogSnapshot;
import com.autospec.mapper.WorkflowOutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

public class WorkflowBacklogMetrics {
    public static final String OUTBOX_PENDING = "autospec.workflow.outbox.pending";
    public static final String OUTBOX_OLDEST_AGE_SECONDS =
            "autospec.workflow.outbox.oldest.age.seconds";
    public static final String REDIS_STREAM_PENDING = "autospec.redis.stream.pending";
    public static final String REDIS_STREAM_OLDEST_IDLE_SECONDS =
            "autospec.redis.stream.oldest.idle.seconds";
    public static final String COLLECTION_FAILURES =
            "autospec.workflow.backlog.collection.failures";

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowBacklogMetrics.class);

    private final WorkflowOutboxMapper outboxMapper;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final String eventStream;
    private final String consumerGroup;
    private final AtomicReference<Double> outboxPending = value();
    private final AtomicReference<Double> outboxOldestAgeSeconds = value();
    private final AtomicReference<Double> redisPending = value();
    private final AtomicReference<Double> redisOldestIdleSeconds = value();
    private final Counter mysqlCollectionFailures;
    private final Counter redisCollectionFailures;

    public WorkflowBacklogMetrics(
            WorkflowOutboxMapper outboxMapper,
            StringRedisTemplate redisTemplate,
            MeterRegistry registry,
            String eventStream,
            String consumerGroup
    ) {
        this(
                outboxMapper,
                redisTemplate,
                registry,
                eventStream,
                consumerGroup,
                Clock.systemDefaultZone()
        );
    }

    WorkflowBacklogMetrics(
            WorkflowOutboxMapper outboxMapper,
            StringRedisTemplate redisTemplate,
            MeterRegistry registry,
            String eventStream,
            String consumerGroup,
            Clock clock
    ) {
        this.outboxMapper = outboxMapper;
        this.redisTemplate = redisTemplate;
        this.eventStream = eventStream;
        this.consumerGroup = consumerGroup;
        this.clock = clock;

        gauge(registry, OUTBOX_PENDING, "Pending workflow outbox commands", outboxPending);
        gauge(
                registry,
                OUTBOX_OLDEST_AGE_SECONDS,
                "Age of the oldest pending workflow outbox command",
                outboxOldestAgeSeconds
        );
        gauge(
                registry,
                REDIS_STREAM_PENDING,
                "Pending workflow events in the control-plane consumer group",
                redisPending,
                eventStream,
                consumerGroup
        );
        gauge(
                registry,
                REDIS_STREAM_OLDEST_IDLE_SECONDS,
                "Idle time of the oldest pending workflow event by stream id",
                redisOldestIdleSeconds,
                eventStream,
                consumerGroup
        );
        this.mysqlCollectionFailures = failureCounter(registry, "mysql");
        this.redisCollectionFailures = failureCounter(registry, "redis");
    }

    @Scheduled(
            fixedDelayString = "${autospec.observability.workflow-backlog.fixed-delay:15000}",
            initialDelayString = "${autospec.observability.workflow-backlog.initial-delay:5000}"
    )
    public void refresh() {
        refreshOutbox();
        refreshRedisPending();
    }

    private void refreshOutbox() {
        try {
            WorkflowOutboxBacklogSnapshot snapshot = outboxMapper.selectPendingBacklog();
            long pending = snapshot == null || snapshot.getPendingCount() == null
                    ? 0
                    : snapshot.getPendingCount();
            outboxPending.set((double) pending);
            outboxOldestAgeSeconds.set(oldestAge(snapshot, pending));
        } catch (RuntimeException failure) {
            mysqlCollectionFailures.increment();
            logCollectionFailure("mysql", failure);
        }
    }

    private void refreshRedisPending() {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(
                    eventStream,
                    consumerGroup
            );
            long pending = summary == null ? 0 : summary.getTotalPendingMessages();
            redisPending.set((double) pending);
            redisOldestIdleSeconds.set(pending == 0 ? 0 : oldestPendingIdleSeconds());
        } catch (RuntimeException failure) {
            redisCollectionFailures.increment();
            logCollectionFailure("redis", failure);
        }
    }

    private double oldestAge(WorkflowOutboxBacklogSnapshot snapshot, long pending) {
        if (pending == 0 || snapshot == null || snapshot.getOldestCreatedAt() == null) {
            return 0;
        }
        Duration age = Duration.between(snapshot.getOldestCreatedAt(), LocalDateTime.now(clock));
        return Math.max(0, age.toMillis() / 1000.0);
    }

    private double oldestPendingIdleSeconds() {
        PendingMessages pendingMessages = redisTemplate.opsForStream().pending(
                eventStream,
                consumerGroup,
                Range.unbounded(),
                1
        );
        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return 0;
        }
        return Math.max(
                0,
                pendingMessages.get(0).getElapsedTimeSinceLastDelivery().toMillis() / 1000.0
        );
    }

    private void logCollectionFailure(String source, RuntimeException failure) {
        LOGGER.warn(
                "Workflow backlog metric collection failed: source={}, errorClass={}",
                source,
                failure.getClass().getSimpleName()
        );
    }

    private void gauge(
            MeterRegistry registry,
            String name,
            String description,
            AtomicReference<Double> value
    ) {
        Gauge.builder(name, value, AtomicReference::get)
                .description(description)
                .register(registry);
    }

    private void gauge(
            MeterRegistry registry,
            String name,
            String description,
            AtomicReference<Double> value,
            String stream,
            String group
    ) {
        Gauge.builder(name, value, AtomicReference::get)
                .description(description)
                .tag("stream", stream)
                .tag("consumer_group", group)
                .register(registry);
    }

    private Counter failureCounter(MeterRegistry registry, String source) {
        return Counter.builder(COLLECTION_FAILURES)
                .description("Workflow backlog metric collection failures")
                .tag("source", source)
                .register(registry);
    }

    private static AtomicReference<Double> value() {
        return new AtomicReference<>(Double.NaN);
    }
}
