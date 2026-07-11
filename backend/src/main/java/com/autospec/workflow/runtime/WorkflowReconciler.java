package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorkflowReconciler {
    private final WorkflowSchedulingGateway gateway;
    private final NodeReadinessEvaluator readinessEvaluator;

    public WorkflowReconciler(
            WorkflowSchedulingGateway gateway,
            NodeReadinessEvaluator readinessEvaluator
    ) {
        this.gateway = gateway;
        this.readinessEvaluator = readinessEvaluator;
    }

    public ReconciliationResult reconcile(long workflowRunId, CompiledWorkflow graph) {
        List<WorkflowNodeRun> runs = gateway.listNodeRuns(workflowRunId);
        Map<String, WorkflowNodeRun> latestRuns = new LinkedHashMap<>();
        for (WorkflowNodeRun run : runs) {
            WorkflowNodeRun current = latestRuns.get(run.getNodeId());
            if (current == null || isNewer(run, current)) {
                latestRuns.put(run.getNodeId(), run);
            }
        }

        Map<String, WorkflowNodeStatus> statuses = new LinkedHashMap<>();
        latestRuns.forEach((nodeId, run) -> statuses.put(nodeId, WorkflowNodeStatus.valueOf(run.getStatus())));
        int activeCount = (int) statuses.values().stream()
                .filter(status -> status == WorkflowNodeStatus.QUEUED || status == WorkflowNodeStatus.RUNNING)
                .count();
        NodeSchedulingPlan plan = readinessEvaluator.evaluate(graph, statuses, activeCount);

        List<String> queued = new ArrayList<>();
        List<String> concurrentChanges = new ArrayList<>();
        for (String nodeId : plan.readyNodes()) {
            WorkflowNodeRun nodeRun = latestRuns.get(nodeId);
            if (nodeRun == null) {
                concurrentChanges.add(nodeId);
                continue;
            }
            String executionId = workflowRunId + ":" + nodeId + ":"
                    + nodeRun.getRevision() + ":" + nodeRun.getAttempt();
            QueuedNodeCommand command = new QueuedNodeCommand(
                    UUID.randomUUID().toString(),
                    workflowRunId,
                    nodeRun.getId(),
                    nodeId,
                    nodeRun.getRevision(),
                    nodeRun.getAttempt(),
                    executionId
            );
            if (gateway.reserveAndAppendCommand(nodeRun, command)) {
                queued.add(nodeId);
            } else {
                concurrentChanges.add(nodeId);
            }
        }
        return new ReconciliationResult(queued, concurrentChanges, plan.blockedNodes());
    }

    private boolean isNewer(WorkflowNodeRun candidate, WorkflowNodeRun current) {
        int revisionComparison = Integer.compare(candidate.getRevision(), current.getRevision());
        return revisionComparison > 0
                || revisionComparison == 0 && candidate.getAttempt() > current.getAttempt();
    }
}
