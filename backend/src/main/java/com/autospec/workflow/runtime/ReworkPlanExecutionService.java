package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReworkPlanExecutionService {
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final ReworkPlanner reworkPlanner;
    private final WorkflowSchedulingGateway schedulingGateway;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;

    public ReworkPlanExecutionService(
            WorkflowRunMapper workflowRunMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            ReworkPlanner reworkPlanner,
            WorkflowSchedulingGateway schedulingGateway,
            WorkflowRunReconciliationTrigger reconciliationTrigger
    ) {
        this.workflowRunMapper = workflowRunMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.reworkPlanner = reworkPlanner;
        this.schedulingGateway = schedulingGateway;
        this.reconciliationTrigger = reconciliationTrigger;
    }

    @Transactional
    public ReworkPlanner.ReworkPlan execute(
            long workflowRunId,
            String reviewerNodeId,
            List<String> requestedTargets
    ) {
        WorkflowRun workflowRun = requireWorkflowRun(workflowRunId);
        CompiledWorkflow graph = dagCompiler.compile(
                snapshotParser.parse(workflowRun.getWorkflowSnapshotJson())
        );
        Map<String, WorkflowNodeRun> latestRuns = latestRuns(
                schedulingGateway.listNodeRuns(workflowRunId)
        );
        int reviewRound = valueOrZero(workflowRun.getReviewRound());
        int maxReviewRounds = valueOrZero(workflowRun.getMaxReviewRounds());
        ReworkPlanner.ReworkPlan plan = reworkPlanner.plan(
                graph,
                reviewerNodeId,
                requestedTargets,
                latestRuns,
                reviewRound,
                maxReviewRounds
        );

        if (plan.action() == ReworkPlanner.Action.MANUAL_INTERVENTION) {
            moveToManualIntervention(workflowRun);
            return plan;
        }

        validateTargetsAreCompleted(plan);
        advanceReviewRound(workflowRun, plan.nextReviewRound());
        LocalDateTime now = LocalDateTime.now();
        for (String nodeId : plan.staleNodeIds()) {
            WorkflowNodeRun previousRevision = latestRuns.get(nodeId);
            markStale(previousRevision, now);
            insertPendingRevision(previousRevision, now);
        }

        reconciliationTrigger.reconcile(workflowRunId);
        return plan;
    }

    private void validateTargetsAreCompleted(ReworkPlanner.ReworkPlan plan) {
        for (String target : plan.targetRevisions().keySet()) {
            if (!plan.staleNodeIds().contains(target)) {
                throw new IllegalStateException(
                        "rework target must have a completed current revision: " + target
                );
            }
        }
    }

    private WorkflowRun requireWorkflowRun(long workflowRunId) {
        WorkflowRun workflowRun = workflowRunMapper.selectById(workflowRunId);
        if (workflowRun == null) {
            throw new IllegalArgumentException("workflow run not found: " + workflowRunId);
        }
        if (workflowRun.getWorkflowSnapshotJson() == null
                || workflowRun.getWorkflowSnapshotJson().isBlank()) {
            throw new IllegalArgumentException(
                    "workflow run has no frozen workflow snapshot: " + workflowRunId
            );
        }
        return workflowRun;
    }

    private Map<String, WorkflowNodeRun> latestRuns(List<WorkflowNodeRun> runs) {
        Map<String, WorkflowNodeRun> latest = new LinkedHashMap<>();
        for (WorkflowNodeRun candidate : runs) {
            WorkflowNodeRun current = latest.get(candidate.getNodeId());
            if (current == null || isNewer(candidate, current)) {
                latest.put(candidate.getNodeId(), candidate);
            }
        }
        return latest;
    }

    private boolean isNewer(WorkflowNodeRun candidate, WorkflowNodeRun current) {
        int revisionComparison = Integer.compare(candidate.getRevision(), current.getRevision());
        return revisionComparison > 0
                || revisionComparison == 0 && candidate.getAttempt() > current.getAttempt();
    }

    private void advanceReviewRound(WorkflowRun workflowRun, int nextReviewRound) {
        int lockVersion = valueOrZero(workflowRun.getLockVersion());
        LocalDateTime now = LocalDateTime.now();
        int updated = workflowRunMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, workflowRun.getId())
                .eq(WorkflowRun::getReviewRound, valueOrZero(workflowRun.getReviewRound()))
                .eq(WorkflowRun::getLockVersion, lockVersion)
                .set(WorkflowRun::getReviewRound, nextReviewRound)
                .set(WorkflowRun::getLockVersion, lockVersion + 1)
                .set(WorkflowRun::getUpdatedAt, now));
        if (updated == 0) {
            throw concurrentChange("workflow run", workflowRun.getId());
        }
    }

    private void moveToManualIntervention(WorkflowRun workflowRun) {
        int lockVersion = valueOrZero(workflowRun.getLockVersion());
        LocalDateTime now = LocalDateTime.now();
        int updated = workflowRunMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, workflowRun.getId())
                .eq(WorkflowRun::getLockVersion, lockVersion)
                .set(WorkflowRun::getStatus, "MANUAL_INTERVENTION")
                .set(WorkflowRun::getResponseStatus, "MANUAL_INTERVENTION")
                .set(WorkflowRun::getLockVersion, lockVersion + 1)
                .set(WorkflowRun::getUpdatedAt, now));
        if (updated == 0) {
            throw concurrentChange("workflow run", workflowRun.getId());
        }
    }

    private void markStale(WorkflowNodeRun nodeRun, LocalDateTime now) {
        if (nodeRun == null) {
            throw new IllegalStateException("missing node run selected by rework plan");
        }
        int lockVersion = valueOrZero(nodeRun.getLockVersion());
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getStatus, nodeRun.getStatus())
                .eq(WorkflowNodeRun::getLockVersion, lockVersion)
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.STALE.name())
                .set(WorkflowNodeRun::getLockVersion, lockVersion + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (updated == 0) {
            throw concurrentChange("workflow node run", nodeRun.getId());
        }
    }

    private void insertPendingRevision(WorkflowNodeRun previous, LocalDateTime now) {
        WorkflowNodeRun revision = new WorkflowNodeRun();
        revision.setWorkflowRunId(previous.getWorkflowRunId());
        revision.setNodeId(previous.getNodeId());
        revision.setRevision(previous.getRevision() + 1);
        revision.setAttempt(1);
        revision.setExecutionId("pending:" + UUID.randomUUID());
        revision.setStatus(WorkflowNodeStatus.PENDING.name());
        revision.setHandlerKey(previous.getHandlerKey());
        revision.setHandlerVersion(previous.getHandlerVersion());
        revision.setTimeoutMs(previous.getTimeoutMs());
        revision.setInputJson(previous.getInputJson());
        revision.setLockVersion(0);
        revision.setCreatedAt(now);
        revision.setUpdatedAt(now);
        nodeRunMapper.insert(revision);
    }

    private IllegalStateException concurrentChange(String aggregate, long id) {
        return new IllegalStateException(
                "concurrent change while applying rework plan to " + aggregate + ": " + id
        );
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
