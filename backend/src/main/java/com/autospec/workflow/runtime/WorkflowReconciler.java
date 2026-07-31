package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.observability.WorkflowTraceContextFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WorkflowReconciler {
    private final WorkflowSchedulingGateway gateway;
    private final NodeReadinessEvaluator readinessEvaluator;
    private final ObjectMapper objectMapper;
    private final WorkflowApprovalCoordinator approvalCoordinator;
    private final WorkflowNodeInputAssembler inputAssembler;
    private final WorkflowTraceContextFactory traceContextFactory;

    @Autowired
    public WorkflowReconciler(
            WorkflowSchedulingGateway gateway,
            NodeReadinessEvaluator readinessEvaluator,
            ObjectMapper objectMapper,
            WorkflowApprovalCoordinator approvalCoordinator,
            WorkflowNodeInputAssembler inputAssembler,
            WorkflowTraceContextFactory traceContextFactory
    ) {
        this.gateway = gateway;
        this.readinessEvaluator = readinessEvaluator;
        this.objectMapper = objectMapper;
        this.approvalCoordinator = approvalCoordinator;
        this.inputAssembler = inputAssembler;
        this.traceContextFactory = traceContextFactory;
    }

    public WorkflowReconciler(
            WorkflowSchedulingGateway gateway,
            NodeReadinessEvaluator readinessEvaluator,
            ObjectMapper objectMapper,
            WorkflowApprovalCoordinator approvalCoordinator,
            WorkflowNodeInputAssembler inputAssembler
    ) {
        this(
                gateway,
                readinessEvaluator,
                objectMapper,
                approvalCoordinator,
                inputAssembler,
                new WorkflowTraceContextFactory()
        );
    }

    public WorkflowReconciler(
            WorkflowSchedulingGateway gateway,
            NodeReadinessEvaluator readinessEvaluator,
            ObjectMapper objectMapper
    ) {
        this(
                gateway,
                readinessEvaluator,
                objectMapper,
                WorkflowApprovalCoordinator.none(),
                null,
                new WorkflowTraceContextFactory()
        );
    }

    public ReconciliationResult reconcile(long workflowRunId, CompiledWorkflow graph) {
        return reconcile(workflowRunId, Long.toString(workflowRunId), graph);
    }

    public ReconciliationResult reconcile(
            long workflowRunId,
            String correlationId,
            CompiledWorkflow graph
    ) {
        WorkflowTraceContextFactory.Context traceContext = traceContextFactory.create(correlationId);
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
            if (approvalCoordinator.pauseBeforeIfRequired(graph, nodeRun)) {
                continue;
            }
            if (inputAssembler != null) {
                inputAssembler.assemble(graph, nodeRun);
            }
            String executionId = workflowRunId + ":" + nodeId + ":"
                    + nodeRun.getRevision() + ":" + nodeRun.getAttempt();
            QueuedNodeCommand command = QueuedNodeCommand.fromNodeRun(
                    UUID.randomUUID().toString(),
                    nodeRun,
                    executionId,
                    traceContext,
                    objectMapper
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
