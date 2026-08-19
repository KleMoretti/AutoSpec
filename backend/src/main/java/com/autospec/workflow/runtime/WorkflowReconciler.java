package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.observability.WorkflowTraceContextFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
        List<String> concurrentChanges = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, WorkflowNodeRun> latestRuns = Map.of();
        NodeSchedulingPlan plan = new NodeSchedulingPlan(List.of(), List.of(), List.of());
        for (int pass = 0; pass <= graph.nodes().size(); pass++) {
            latestRuns = latestRuns(gateway.listNodeRuns(workflowRunId));
            Map<String, WorkflowNodeStatus> statuses = statuses(latestRuns);
            int activeCount = (int) statuses.values().stream()
                    .filter(status -> status == WorkflowNodeStatus.QUEUED
                            || status == WorkflowNodeStatus.RUNNING)
                    .count();
            plan = readinessEvaluator.evaluate(
                    graph,
                    statuses,
                    outputs(latestRuns),
                    activeCount
            );
            if (plan.skippedNodes().isEmpty()) {
                break;
            }
            boolean changed = false;
            for (String nodeId : plan.skippedNodes()) {
                WorkflowNodeRun nodeRun = latestRuns.get(nodeId);
                if (nodeRun != null && gateway.markSkipped(
                        nodeRun,
                        "Incoming conditional workflow edge did not match"
                )) {
                    skipped.add(nodeId);
                    changed = true;
                } else {
                    concurrentChanges.add(nodeId);
                }
            }
            if (!changed) {
                break;
            }
        }

        List<String> queued = new ArrayList<>();
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
                    graph.protocolVersion(),
                    graph.nodes().get(nodeId),
                    objectMapper
            );
            if (gateway.reserveAndAppendCommand(nodeRun, command)) {
                queued.add(nodeId);
            } else {
                concurrentChanges.add(nodeId);
            }
        }
        return new ReconciliationResult(queued, concurrentChanges, plan.blockedNodes(), skipped);
    }

    private Map<String, WorkflowNodeRun> latestRuns(List<WorkflowNodeRun> runs) {
        Map<String, WorkflowNodeRun> latest = new LinkedHashMap<>();
        for (WorkflowNodeRun run : runs) {
            WorkflowNodeRun current = latest.get(run.getNodeId());
            if (current == null || isNewer(run, current)) {
                latest.put(run.getNodeId(), run);
            }
        }
        return latest;
    }

    private Map<String, WorkflowNodeStatus> statuses(Map<String, WorkflowNodeRun> runs) {
        Map<String, WorkflowNodeStatus> result = new LinkedHashMap<>();
        runs.forEach((nodeId, run) -> result.put(
                nodeId,
                WorkflowNodeStatus.valueOf(run.getStatus())
        ));
        return result;
    }

    private Map<String, JsonNode> outputs(Map<String, WorkflowNodeRun> runs) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        runs.forEach((nodeId, run) -> {
            if (run.getOutputJson() == null || run.getOutputJson().isBlank()) {
                return;
            }
            try {
                result.put(nodeId, objectMapper.readTree(run.getOutputJson()));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                        "Invalid workflow node output JSON: " + nodeId,
                        exception
                );
            }
        });
        return result;
    }

    private boolean isNewer(WorkflowNodeRun candidate, WorkflowNodeRun current) {
        int revisionComparison = Integer.compare(candidate.getRevision(), current.getRevision());
        return revisionComparison > 0
                || revisionComparison == 0 && candidate.getAttempt() > current.getAttempt();
    }
}
