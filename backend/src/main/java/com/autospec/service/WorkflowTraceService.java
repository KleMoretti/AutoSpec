package com.autospec.service;

import com.autospec.dto.WorkflowFailureCaseResponse;
import com.autospec.dto.WorkflowFailureClusterResponse;
import com.autospec.dto.WorkflowTraceInvocationResponse;
import com.autospec.dto.WorkflowTraceNodeResponse;
import com.autospec.dto.WorkflowTraceResponse;
import com.autospec.dto.WorkflowTraceStepResponse;
import com.autospec.entity.ModelInvocation;
import com.autospec.entity.WorkflowAgentStepFact;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.WorkflowAgentStepFactMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowTraceService {
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ModelInvocationMapper modelInvocationMapper;
    private final WorkflowAgentStepFactMapper agentStepFactMapper;

    public WorkflowTraceService(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ModelInvocationMapper modelInvocationMapper,
            WorkflowAgentStepFactMapper agentStepFactMapper
    ) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.modelInvocationMapper = modelInvocationMapper;
        this.agentStepFactMapper = agentStepFactMapper;
    }

    public WorkflowTraceResponse trace(long workflowRunId) {
        WorkflowRun run = runMapper.selectById(workflowRunId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        List<WorkflowNodeRun> nodes = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, workflowRunId)
                        .orderByAsc(WorkflowNodeRun::getId)
        );
        List<ModelInvocation> invocations = modelInvocationMapper.selectList(
                new LambdaQueryWrapper<ModelInvocation>()
                        .eq(ModelInvocation::getWorkflowRunId, workflowRunId)
                        .orderByAsc(ModelInvocation::getId)
        );
        List<WorkflowAgentStepFact> steps = agentStepFactMapper.selectList(
                new LambdaQueryWrapper<WorkflowAgentStepFact>()
                        .eq(WorkflowAgentStepFact::getWorkflowRunId, workflowRunId)
                        .orderByAsc(WorkflowAgentStepFact::getNodeRunId)
                        .orderByAsc(WorkflowAgentStepFact::getStep)
                        .orderByAsc(WorkflowAgentStepFact::getId)
        );
        Map<Long, List<ModelInvocation>> byNodeRun = invocations.stream()
                .filter(invocation -> invocation.getWorkflowNodeRunId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        ModelInvocation::getWorkflowNodeRunId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
        Map<String, FailureCluster> clusters = new LinkedHashMap<>();
        Map<Long, List<WorkflowAgentStepFact>> stepsByNodeRun = steps.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        WorkflowAgentStepFact::getNodeRunId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
        List<WorkflowFailureCaseResponse> offlineCases = new ArrayList<>();
        List<WorkflowTraceNodeResponse> traceNodes = new ArrayList<>();
        for (WorkflowNodeRun node : nodes) {
            List<ModelInvocation> nodeInvocations = byNodeRun.getOrDefault(node.getId(), List.of());
            if ("FAILED".equals(node.getStatus())) {
                String errorCode = valueOrUnknown(node.getErrorCode());
                cluster(clusters, "NODE", errorCode).add(node.getId());
                offlineCases.add(failureCase(run, node, nodeInvocations, errorCode));
            }
            traceNodes.add(new WorkflowTraceNodeResponse(
                    node.getId(),
                    node.getNodeId(),
                    node.getRevision(),
                    node.getAttempt(),
                    node.getExecutionId(),
                    node.getContractHash(),
                    node.getExecutionBundleHash(),
                    node.getFencingToken(),
                    node.getStatus(),
                    node.getHandlerKey(),
                    node.getHandlerVersion(),
                    node.getDurationMs(),
                    queueTime(node),
                    node.getActualInputTokens(),
                    node.getActualOutputTokens(),
                    node.getActualCacheTokens(),
                    node.getActualCost(),
                    node.getActualModelCalls(),
                    node.getActualToolCalls(),
                    node.getErrorCode(),
                    node.getWorkerId(),
                    nodeInvocations.stream().map(this::invocation).toList(),
                    stepsByNodeRun.getOrDefault(node.getId(), List.of())
                            .stream()
                            .map(this::step)
                            .toList()
            ));
        }
        for (ModelInvocation invocation : invocations) {
            if ("FAILED".equals(invocation.getStatus())) {
                String dimension = "TOOL".equals(invocation.getCallType()) ? "TOOL" : "MODEL";
                cluster(clusters, dimension, valueOrUnknown(invocation.getErrorCode()))
                        .add(invocation.getWorkflowNodeRunId());
            }
        }
        return new WorkflowTraceResponse(
                run.getId(),
                run.getCorrelationId(),
                run.getStatus(),
                run.getExecutionBundleId(),
                run.getExecutionBundleHash(),
                traceNodes,
                clusters.values().stream().map(FailureCluster::response).toList(),
                offlineCases
        );
    }

    private WorkflowTraceInvocationResponse invocation(ModelInvocation invocation) {
        return new WorkflowTraceInvocationResponse(
                invocation.getId(),
                invocation.getCallType(),
                invocation.getProviderKey(),
                invocation.getModelName(),
                invocation.getPromptKey(),
                invocation.getPromptVersion(),
                invocation.getRouteKey(),
                invocation.getToolName(),
                invocation.getToolVersion(),
                invocation.getStatus(),
                invocation.getDurationMs(),
                invocation.getInputTokens(),
                invocation.getOutputTokens(),
                invocation.getCacheTokens(),
                invocation.getEstimatedCost(),
                invocation.getErrorCode()
        );
    }

    private WorkflowTraceStepResponse step(WorkflowAgentStepFact step) {
        List<String> issueCodes = List.of();
        if (step.getValidationIssueCodesJson() != null
                && !step.getValidationIssueCodesJson().isBlank()) {
            try {
                issueCodes = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(
                                step.getValidationIssueCodesJson(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }
                        );
            } catch (java.io.IOException ignored) {
                issueCodes = List.of("TRACE_DECODE_ERROR");
            }
        }
        return new WorkflowTraceStepResponse(
                step.getId(),
                step.getStep(),
                step.getPhase(),
                step.getStatus(),
                step.getReasonCode(),
                step.getPlanHash(),
                step.getObservationHash(),
                issueCodes,
                step.getModelCallRef(),
                step.getToolCallRef(),
                step.getStartedAtEpochMs(),
                step.getFinishedAtEpochMs(),
                step.getDurationMs()
        );
    }

    private WorkflowFailureCaseResponse failureCase(
            WorkflowRun run,
            WorkflowNodeRun node,
            List<ModelInvocation> invocations,
            String errorCode
    ) {
        ModelInvocation model = invocations.stream()
                .filter(invocation -> !"TOOL".equals(invocation.getCallType()))
                .findFirst()
                .orElse(null);
        ModelInvocation reference = model == null && !invocations.isEmpty()
                ? invocations.get(0)
                : model;
        return new WorkflowFailureCaseResponse(
                "workflow-failure:" + run.getId() + ":" + node.getId(),
                run.getId(),
                node.getId(),
                node.getNodeId(),
                node.getHandlerKey(),
                node.getHandlerVersion(),
                reference == null ? null : reference.getModelName(),
                reference == null ? null : reference.getPromptKey(),
                reference == null ? null : reference.getPromptVersion(),
                reference == null ? null : reference.getRouteKey(),
                errorCode,
                node.getFinishedAt() == null ? node.getUpdatedAt() : node.getFinishedAt()
        );
    }

    private FailureCluster cluster(
            Map<String, FailureCluster> clusters,
            String dimension,
            String key
    ) {
        return clusters.computeIfAbsent(
                dimension + "\u0000" + key,
                ignored -> new FailureCluster(dimension, key)
        );
    }

    private long queueTime(WorkflowNodeRun node) {
        if (node.getQueuedAt() == null || node.getStartedAt() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(node.getQueuedAt(), node.getStartedAt()).toMillis());
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static final class FailureCluster {
        private final String dimension;
        private final String key;
        private final List<Long> nodeRunIds = new ArrayList<>();

        private FailureCluster(String dimension, String key) {
            this.dimension = dimension;
            this.key = key;
        }

        private void add(Long nodeRunId) {
            if (nodeRunId != null && !nodeRunIds.contains(nodeRunId)) {
                nodeRunIds.add(nodeRunId);
            }
        }

        private WorkflowFailureClusterResponse response() {
            return new WorkflowFailureClusterResponse(
                    dimension,
                    key,
                    nodeRunIds.size(),
                    List.copyOf(nodeRunIds)
            );
        }
    }
}
