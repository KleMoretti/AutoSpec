package com.autospec.workflow.transport;

import com.autospec.dto.WorkflowOutboxBacklogSnapshot;
import com.autospec.exception.RetryAfterResponseStatusException;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class WorkflowAdmissionGuard {
    public static final String REJECTIONS = "autospec.workflow.admission.rejections";
    static final String WORKER_GROUP = "autospec-workers";

    private final WorkflowOutboxMapper outboxMapper;
    private final WorkflowRunMapper runMapper;
    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final long maxPendingOutbox;
    private final Duration maxOldestOutboxAge;
    private final long maxWorkerBacklog;
    private final long maxActiveRunsPerUser;
    private final long retryAfterSeconds;
    private final Clock clock;
    private final Counter pendingCountRejections;
    private final Counter oldestAgeRejections;
    private final Counter workerBacklogRejections;
    private final Counter outboxUnavailableRejections;
    private final Counter workerUnavailableRejections;
    private final Counter perUserRejections;

    @Autowired
    public WorkflowAdmissionGuard(
            WorkflowOutboxMapper outboxMapper,
            WorkflowRunMapper runMapper,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${autospec.workflow.admission.enabled:true}") boolean enabled,
            @Value("${autospec.workflow.admission.max-pending-outbox:10000}")
            long maxPendingOutbox,
            @Value("${autospec.workflow.admission.max-oldest-outbox-age:5m}")
            Duration maxOldestOutboxAge,
            @Value("${autospec.workflow.admission.max-worker-backlog:1000}")
            long maxWorkerBacklog,
            @Value("${autospec.workflow.admission.retry-after:5s}") Duration retryAfter,
            @Value("${autospec.workflow.admission.max-active-runs-per-user:3}")
            long maxActiveRunsPerUser
    ) {
        this(
                outboxMapper,
                runMapper,
                redisTemplate,
                meterRegistry,
                enabled,
                maxPendingOutbox,
                maxOldestOutboxAge,
                maxWorkerBacklog,
                retryAfter,
                maxActiveRunsPerUser,
                Clock.systemDefaultZone()
        );
    }

    public WorkflowAdmissionGuard(
            WorkflowOutboxMapper outboxMapper,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            boolean enabled,
            long maxPendingOutbox,
            Duration maxOldestOutboxAge,
            long maxWorkerBacklog,
            Duration retryAfter
    ) {
        this(
                outboxMapper,
                null,
                redisTemplate,
                meterRegistry,
                enabled,
                maxPendingOutbox,
                maxOldestOutboxAge,
                maxWorkerBacklog,
                retryAfter,
                0,
                Clock.systemDefaultZone()
        );
    }

    WorkflowAdmissionGuard(
            WorkflowOutboxMapper outboxMapper,
            WorkflowRunMapper runMapper,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            boolean enabled,
            long maxPendingOutbox,
            Duration maxOldestOutboxAge,
            long maxWorkerBacklog,
            Duration retryAfter,
            long maxActiveRunsPerUser,
            Clock clock
    ) {
        if (maxPendingOutbox < 1) {
            throw new IllegalArgumentException("Maximum pending outbox count must be positive");
        }
        if (maxOldestOutboxAge.isZero() || maxOldestOutboxAge.isNegative()) {
            throw new IllegalArgumentException("Maximum outbox age must be positive");
        }
        if (maxWorkerBacklog < 1) {
            throw new IllegalArgumentException("Maximum worker backlog must be positive");
        }
        if (retryAfter.isZero() || retryAfter.isNegative()) {
            throw new IllegalArgumentException("Workflow Retry-After must be positive");
        }
        if (maxActiveRunsPerUser < 0) {
            throw new IllegalArgumentException("Maximum active runs per user must not be negative");
        }
        this.outboxMapper = outboxMapper;
        this.runMapper = runMapper;
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.maxPendingOutbox = maxPendingOutbox;
        this.maxOldestOutboxAge = maxOldestOutboxAge;
        this.maxWorkerBacklog = maxWorkerBacklog;
        this.maxActiveRunsPerUser = maxActiveRunsPerUser;
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1_000);
        this.clock = clock;
        this.pendingCountRejections = counter(meterRegistry, "pending_count");
        this.oldestAgeRejections = counter(meterRegistry, "oldest_age");
        this.workerBacklogRejections = counter(meterRegistry, "worker_backlog");
        this.outboxUnavailableRejections = counter(meterRegistry, "outbox_store_unavailable");
        this.workerUnavailableRejections = counter(meterRegistry, "worker_store_unavailable");
        this.perUserRejections = counter(meterRegistry, "per_user_active");
    }

    WorkflowAdmissionGuard(
            WorkflowOutboxMapper outboxMapper,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            boolean enabled,
            long maxPendingOutbox,
            Duration maxOldestOutboxAge,
            long maxWorkerBacklog,
            Duration retryAfter,
            Clock clock
    ) {
        this(
                outboxMapper,
                null,
                redisTemplate,
                meterRegistry,
                enabled,
                maxPendingOutbox,
                maxOldestOutboxAge,
                maxWorkerBacklog,
                retryAfter,
                0,
                clock
        );
    }

    public void admit() {
        admit(null);
    }

    public void admit(Long userId) {
        if (!enabled) {
            return;
        }
        WorkflowOutboxBacklogSnapshot snapshot;
        try {
            snapshot = outboxMapper.selectPendingBacklog();
        } catch (RuntimeException exception) {
            outboxUnavailableRejections.increment();
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
        long workerBacklog;
        try {
            workerBacklog = workerBacklog();
        } catch (RuntimeException exception) {
            workerUnavailableRejections.increment();
            throw rejected("Worker admission state is temporarily unavailable", exception);
        }
        if (workerBacklog >= maxWorkerBacklog) {
            workerBacklogRejections.increment();
            throw rejected("Workflow admission paused by worker command backlog", null);
        }
        if (userId != null && runMapper != null && maxActiveRunsPerUser > 0) {
            long activeRuns;
            try {
                activeRuns = runMapper.selectCount(new LambdaQueryWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getInitiatedByUserId, userId)
                        .eq(WorkflowRun::getStatus, "RUNNING"));
            } catch (RuntimeException exception) {
                perUserRejections.increment();
                throw rejected("User workflow admission state is temporarily unavailable", exception);
            }
            if (activeRuns >= maxActiveRunsPerUser) {
                perUserRejections.increment();
                throw rejected("Workflow admission paused by per-user active run limit", null);
            }
        }
    }

    private long workerBacklog() {
        Long streamSize = redisTemplate.opsForStream().size(WorkflowOutboxPublisher.COMMAND_STREAM);
        if (streamSize == null || streamSize == 0) {
            return 0;
        }
        StreamInfo.XInfoGroup group = redisTemplate.opsForStream()
                .groups(WorkflowOutboxPublisher.COMMAND_STREAM)
                .stream()
                .filter(candidate -> WORKER_GROUP.equals(candidate.groupName()))
                .findFirst()
                .orElse(null);
        if (group == null) {
            return streamSize;
        }
        long pending = group.pendingCount() == null ? 0 : group.pendingCount();
        return Math.addExact(pending, numericValue(group.getRaw().get("lag")));
    }

    private long numericValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        if (value != null) {
            return Long.parseLong(value.toString());
        }
        throw new IllegalStateException("Redis worker group lag is unavailable");
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
