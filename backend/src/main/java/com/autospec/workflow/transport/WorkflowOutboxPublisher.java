package com.autospec.workflow.transport;

import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkflowOutboxPublisher {
    public static final String COMMAND_STREAM = "autospec.workflow.commands";
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowOutboxPublisher.class);

    private final WorkflowOutboxMapper outboxMapper;
    private final WorkflowCommandPublisher commandPublisher;
    private final OutboxRetryPolicy retryPolicy;
    private final WorkflowTransportMetrics metrics;

    public WorkflowOutboxPublisher(
            WorkflowOutboxMapper outboxMapper,
            WorkflowCommandPublisher commandPublisher,
            OutboxRetryPolicy retryPolicy
    ) {
        this(
                outboxMapper,
                commandPublisher,
                retryPolicy,
                WorkflowTransportMetrics.isolated()
        );
    }

    @Autowired
    public WorkflowOutboxPublisher(
            WorkflowOutboxMapper outboxMapper,
            WorkflowCommandPublisher commandPublisher,
            OutboxRetryPolicy retryPolicy,
            WorkflowTransportMetrics metrics
    ) {
        this.outboxMapper = outboxMapper;
        this.commandPublisher = commandPublisher;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
    }

    public int publishPending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowOutbox> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<WorkflowOutbox>()
                        .eq(WorkflowOutbox::getStatus, "PENDING")
                        .and(wrapper -> wrapper
                                .isNull(WorkflowOutbox::getNextRetryAt)
                                .or()
                                .le(WorkflowOutbox::getNextRetryAt, now))
                        .orderByAsc(WorkflowOutbox::getId)
                        .last("limit " + safeLimit)
        );
        int published = 0;
        for (WorkflowOutbox outbox : pending) {
            long publishStartedAt = System.nanoTime();
            try {
                commandPublisher.publish(
                        COMMAND_STREAM,
                        outbox.getEventId(),
                        outbox.getPayloadJson()
                );
            } catch (RuntimeException exception) {
                metrics.recordOutboxPublishFailure();
                scheduleRetry(outbox, now, exception);
                continue;
            } finally {
                metrics.recordOutboxPublishDuration(
                        System.nanoTime() - publishStartedAt
                );
            }
            metrics.recordOutboxPublishSuccess();
            int updated = outboxMapper.update(null, new UpdateWrapper<WorkflowOutbox>()
                    .eq("id", outbox.getId())
                    .eq("status", "PENDING")
                    .set("status", "PUBLISHED")
                    .set("published_at", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now()));
            published += updated;
        }
        return published;
    }

    private void scheduleRetry(
            WorkflowOutbox outbox,
            LocalDateTime now,
            RuntimeException exception
    ) {
        int nextRetryCount = Math.max(0, outbox.getRetryCount() == null
                ? 0
                : outbox.getRetryCount()) + 1;
        LocalDateTime nextRetryAt = retryPolicy.nextRetryAt(nextRetryCount, now);
        int updated = outboxMapper.update(null, new UpdateWrapper<WorkflowOutbox>()
                .eq("id", outbox.getId())
                .eq("status", "PENDING")
                .setSql("retry_count = retry_count + 1")
                .set("next_retry_at", nextRetryAt)
                .set("updated_at", now));
        if (updated == 1) {
            metrics.recordOutboxRetry();
            LOGGER.warn(
                    "Scheduled outbox retry: eventId={}, retryCount={}, nextRetryAt={}, errorClass={}",
                    outbox.getEventId(),
                    nextRetryCount,
                    nextRetryAt,
                    exception.getClass().getSimpleName()
            );
        }
    }
}
