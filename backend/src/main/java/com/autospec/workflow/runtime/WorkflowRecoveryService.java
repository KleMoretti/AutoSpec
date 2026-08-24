package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.observability.WorkflowTraceContextFactory;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class WorkflowRecoveryService {
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;
    private final WorkflowRunMapper runMapper;
    private final WorkflowTraceContextFactory traceContextFactory;

    @Autowired
    public WorkflowRecoveryService(
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowOutboxMapper outboxMapper,
            ObjectMapper objectMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            WorkflowRunMapper runMapper,
            WorkflowTraceContextFactory traceContextFactory
    ) {
        this.nodeRunMapper = nodeRunMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.reconciliationTrigger = reconciliationTrigger;
        this.runMapper = runMapper;
        this.traceContextFactory = traceContextFactory;
    }

    public WorkflowRecoveryService(
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowOutboxMapper outboxMapper,
            ObjectMapper objectMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger
    ) {
        this(
                nodeRunMapper,
                outboxMapper,
                objectMapper,
                reconciliationTrigger,
                null,
                new WorkflowTraceContextFactory()
        );
    }

    @Transactional
    public RecoveryResult recover(LocalDateTime now, Duration leaseTimeout) {
        if (now == null || leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()) {
            throw new IllegalArgumentException("now and a positive leaseTimeout are required");
        }

        int replacements = 0;
        Set<Long> runsToReconcile = new LinkedHashSet<>();
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
            if (!isRunActive(due.getWorkflowRunId())) {
                continue;
            }
            if (claimDueAttempt(due, now)) {
                nodeRunMapper.insert(replacement(due, now));
                replacements++;
                runsToReconcile.add(due.getWorkflowRunId());
            }
        }

        runsToReconcile.forEach(reconciliationTrigger::reconcile);
        return new RecoveryResult(0, replacements, 0);
    }

    private WorkflowNodeRun replacement(WorkflowNodeRun stale, LocalDateTime now) {
        int nextAttempt = stale.getAttempt() + 1;
        WorkflowNodeRun replacement = new WorkflowNodeRun();
        replacement.setWorkflowRunId(stale.getWorkflowRunId());
        replacement.setNodeId(stale.getNodeId());
        replacement.setRevision(stale.getRevision());
        replacement.setAttempt(nextAttempt);
        replacement.setExecutionId(executionId(stale, nextAttempt));
        replacement.setContractHash(stale.getContractHash());
        replacement.setFencingToken(0L);
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

    private boolean claimDueAttempt(WorkflowNodeRun due, LocalDateTime now) {
        int updated = nodeRunMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WorkflowNodeRun>()
                .eq("id", due.getId())
                .inSql("workflow_run_id", activeRunSql(due.getWorkflowRunId()))
                .eq("execution_id", due.getExecutionId())
                .eq("status", due.getStatus())
                .eq("lock_version", due.getLockVersion())
                .eq("next_retry_at", due.getNextRetryAt())
                .set("next_retry_at", null)
                .set("updated_at", now)
                .set("lock_version", due.getLockVersion() + 1));
        return updated == 1;
    }

    private boolean isRunActive(Long workflowRunId) {
        if (runMapper == null) {
            return true;
        }
        WorkflowRun run = runMapper.selectById(workflowRunId);
        return run != null && "RUNNING".equals(run.getStatus());
    }

    private String activeRunSql(Long workflowRunId) {
        if (runMapper == null) {
            return "SELECT " + workflowRunId;
        }
        return "SELECT id FROM workflow_run WHERE id = " + workflowRunId + " AND status = 'RUNNING'";
    }

    public record RecoveryResult(
            int orphanedAttempts,
            int replacementAttempts,
            int compensatedCommands
    ) {
    }
}
