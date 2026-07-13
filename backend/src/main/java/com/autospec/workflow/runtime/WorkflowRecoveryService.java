package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkflowRecoveryService {
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;

    public WorkflowRecoveryService(
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowOutboxMapper outboxMapper,
            ObjectMapper objectMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger
    ) {
        this.nodeRunMapper = nodeRunMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.reconciliationTrigger = reconciliationTrigger;
    }

    @Transactional
    public RecoveryResult recover(LocalDateTime now, Duration leaseTimeout) {
        if (now == null || leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()) {
            throw new IllegalArgumentException("now and a positive leaseTimeout are required");
        }

        int orphaned = 0;
        int replacements = 0;
        Set<Long> runsToReconcile = new LinkedHashSet<>();
        LocalDateTime leaseCutoff = now.minus(leaseTimeout);
        List<WorkflowNodeRun> staleRuns = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.RUNNING.name())
                        .le(WorkflowNodeRun::getHeartbeatAt, leaseCutoff)
                        .orderByAsc(WorkflowNodeRun::getId)
        );
        for (WorkflowNodeRun stale : staleRuns) {
            if (orphan(stale, now)) {
                orphaned++;
                nodeRunMapper.insert(replacement(stale, now));
                replacements++;
                runsToReconcile.add(stale.getWorkflowRunId());
            }
        }

        List<WorkflowNodeRun> dueAttempts = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .in(WorkflowNodeRun::getStatus,
                                WorkflowNodeStatus.RETRY_WAIT.name(),
                                WorkflowNodeStatus.FALLBACK_READY.name())
                        .le(WorkflowNodeRun::getNextRetryAt, now)
                        .orderByAsc(WorkflowNodeRun::getNextRetryAt)
                        .orderByAsc(WorkflowNodeRun::getId)
        );
        for (WorkflowNodeRun due : dueAttempts) {
            if (claimDueAttempt(due, now)) {
                nodeRunMapper.insert(replacement(due, now));
                replacements++;
                runsToReconcile.add(due.getWorkflowRunId());
            }
        }

        int compensated = 0;
        List<WorkflowNodeRun> queuedRuns = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.QUEUED.name())
                        .orderByAsc(WorkflowNodeRun::getId)
        );
        for (WorkflowNodeRun queued : queuedRuns) {
            if (!hasExecuteCommand(queued.getId()) && claimForCompensation(queued, now)) {
                outboxMapper.insert(compensationCommand(queued, now));
                compensated++;
            }
        }
        runsToReconcile.forEach(reconciliationTrigger::reconcile);
        return new RecoveryResult(orphaned, replacements, compensated);
    }

    private boolean orphan(WorkflowNodeRun stale, LocalDateTime now) {
        int updated = nodeRunMapper.update(null, new UpdateWrapper<WorkflowNodeRun>()
                .eq("id", stale.getId())
                .eq("execution_id", stale.getExecutionId())
                .eq("status", WorkflowNodeStatus.RUNNING.name())
                .eq("lock_version", stale.getLockVersion())
                .set("status", WorkflowNodeStatus.ORPHANED.name())
                .set("error_code", "HEARTBEAT_LEASE_EXPIRED")
                .set("error_message", "Worker heartbeat lease expired")
                .set("finished_at", now)
                .set("updated_at", now)
                .set("lock_version", stale.getLockVersion() + 1));
        return updated == 1;
    }

    private WorkflowNodeRun replacement(WorkflowNodeRun stale, LocalDateTime now) {
        int nextAttempt = stale.getAttempt() + 1;
        WorkflowNodeRun replacement = new WorkflowNodeRun();
        replacement.setWorkflowRunId(stale.getWorkflowRunId());
        replacement.setNodeId(stale.getNodeId());
        replacement.setRevision(stale.getRevision());
        replacement.setAttempt(nextAttempt);
        replacement.setExecutionId(executionId(stale, nextAttempt));
        replacement.setStatus(WorkflowNodeStatus.PENDING.name());
        replacement.setHandlerKey(stale.getHandlerKey());
        replacement.setHandlerVersion(stale.getHandlerVersion());
        replacement.setTimeoutMs(stale.getTimeoutMs());
        replacement.setInputJson(stale.getInputJson());
        replacement.setLockVersion(0);
        replacement.setCreatedAt(now);
        replacement.setUpdatedAt(now);
        return replacement;
    }

    private String executionId(WorkflowNodeRun stale, int attempt) {
        return stale.getWorkflowRunId() + ":" + stale.getNodeId() + ":"
                + stale.getRevision() + ":" + attempt;
    }

    private boolean hasExecuteCommand(long nodeRunId) {
        return outboxMapper.selectCount(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getEventType, "EXECUTE_NODE")
                .and(wrapper -> wrapper
                        .like(WorkflowOutbox::getPayloadJson, "\"node_run_id\":" + nodeRunId)
                        .or()
                        .like(WorkflowOutbox::getPayloadJson, "\"nodeRunId\":" + nodeRunId))) > 0;
    }

    private boolean claimForCompensation(WorkflowNodeRun queued, LocalDateTime now) {
        int updated = nodeRunMapper.update(null, new UpdateWrapper<WorkflowNodeRun>()
                .eq("id", queued.getId())
                .eq("execution_id", queued.getExecutionId())
                .eq("status", WorkflowNodeStatus.QUEUED.name())
                .eq("lock_version", queued.getLockVersion())
                .set("updated_at", now)
                .set("lock_version", queued.getLockVersion() + 1));
        return updated == 1;
    }

    private boolean claimDueAttempt(WorkflowNodeRun due, LocalDateTime now) {
        int updated = nodeRunMapper.update(null, new UpdateWrapper<WorkflowNodeRun>()
                .eq("id", due.getId())
                .eq("execution_id", due.getExecutionId())
                .eq("status", due.getStatus())
                .eq("lock_version", due.getLockVersion())
                .eq("next_retry_at", due.getNextRetryAt())
                .set("next_retry_at", null)
                .set("updated_at", now)
                .set("lock_version", due.getLockVersion() + 1));
        return updated == 1;
    }

    private WorkflowOutbox compensationCommand(WorkflowNodeRun queued, LocalDateTime now) {
        QueuedNodeCommand command = QueuedNodeCommand.fromNodeRun(
                UUID.randomUUID().toString(), queued, queued.getExecutionId(), objectMapper
        );
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventId(command.eventId());
        outbox.setAggregateId(Long.toString(command.workflowRunId()));
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson(serialize(command));
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    private String serialize(QueuedNodeCommand command) {
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize recovery command", exception);
        }
    }

    public record RecoveryResult(
            int orphanedAttempts,
            int replacementAttempts,
            int compensatedCommands
    ) {
    }
}
