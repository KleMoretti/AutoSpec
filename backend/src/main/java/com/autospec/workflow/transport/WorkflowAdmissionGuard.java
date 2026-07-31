package com.autospec.workflow.transport;

import com.autospec.dto.WorkflowOutboxBacklogSnapshot;
import com.autospec.exception.RetryAfterResponseStatusException;
import com.autospec.mapper.WorkflowOutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class WorkflowAdmissionGuard {
    public static final String REJECTIONS = "autospec.workflow.admission.rejections";

    private final WorkflowOutboxMapper outboxMapper;
    private final boolean enabled;
    private final long maxPendingOutbox;
    private final Duration maxOldestOutboxAge;
    private final long retryAfterSeconds;
    private final Clock clock;
    private final Counter pendingCountRejections;
    private final Counter oldestAgeRejections;
    private final Counter unavailableRejections;

    @Autowired
    public WorkflowAdmissionGuard(
            WorkflowOutboxMapper outboxMapper,
            MeterRegistry meterRegistry,
            @Value("${autospec.workflow.admission.enabled:true}") boolean enabled,
            @Value("${autospec.workflow.admission.max-pending-outbox:10000}")
            long maxPendingOutbox,
            @Value("${autospec.workflow.admission.max-oldest-outbox-age:5m}")
            Duration maxOldestOutboxAge,
            @Value("${autospec.workflow.admission.retry-after:5s}") Duration retryAfter
    ) {
        this(
                outboxMapper,
                meterRegistry,
                enabled,
                maxPendingOutbox,
                maxOldestOutboxAge,
                retryAfter,
                Clock.systemDefaultZone()
        );
    }

    WorkflowAdmissionGuard(
            WorkflowOutboxMapper outboxMapper,
            MeterRegistry meterRegistry,
            boolean enabled,
            long maxPendingOutbox,
            Duration maxOldestOutboxAge,
            Duration retryAfter,
            Clock clock
    ) {
        if (maxPendingOutbox < 1) {
            throw new IllegalArgumentException("Maximum pending outbox count must be positive");
        }
        if (maxOldestOutboxAge.isZero() || maxOldestOutboxAge.isNegative()) {
            throw new IllegalArgumentException("Maximum outbox age must be positive");
        }
        if (retryAfter.isZero() || retryAfter.isNegative()) {
            throw new IllegalArgumentException("Workflow Retry-After must be positive");
        }
        this.outboxMapper = outboxMapper;
        this.enabled = enabled;
        this.maxPendingOutbox = maxPendingOutbox;
        this.maxOldestOutboxAge = maxOldestOutboxAge;
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1_000);
        this.clock = clock;
        this.pendingCountRejections = counter(meterRegistry, "pending_count");
        this.oldestAgeRejections = counter(meterRegistry, "oldest_age");
        this.unavailableRejections = counter(meterRegistry, "store_unavailable");
    }

    public void admit() {
        if (!enabled) {
            return;
        }
        WorkflowOutboxBacklogSnapshot snapshot;
        try {
            snapshot = outboxMapper.selectPendingBacklog();
        } catch (RuntimeException exception) {
            unavailableRejections.increment();
            throw rejected("Workflow admission state is temporarily unavailable", exception);
        }
        long pendingCount = snapshot == null || snapshot.getPendingCount() == null
                ? 0
                : snapshot.getPendingCount();
        if (pendingCount >= maxPendingOutbox) {
            pendingCountRejections.increment();
            throw rejected("Workflow admission paused by pending outbox backlog", null);
        }
        if (oldestAge(snapshot).compareTo(maxOldestOutboxAge) >= 0) {
            oldestAgeRejections.increment();
            throw rejected("Workflow admission paused by stale outbox backlog", null);
        }
    }

    private Duration oldestAge(WorkflowOutboxBacklogSnapshot snapshot) {
        if (snapshot == null || snapshot.getOldestCreatedAt() == null) {
            return Duration.ZERO;
        }
        Duration age = Duration.between(
                snapshot.getOldestCreatedAt(),
                LocalDateTime.now(clock)
        );
        return age.isNegative() ? Duration.ZERO : age;
    }

    private RetryAfterResponseStatusException rejected(String reason, Throwable cause) {
        return new RetryAfterResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                reason,
                retryAfterSeconds,
                cause
        );
    }

    private Counter counter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder(REJECTIONS)
                .description("Workflow admission rejections by backpressure reason")
                .tag("reason", reason)
                .register(meterRegistry);
    }
}
