package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.ModelInvocation;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class WorkflowNodeInputAssembler {
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ObjectMapper objectMapper;
    private final ModelInvocationMapper modelInvocationMapper;

    public WorkflowNodeInputAssembler(
            WorkflowNodeRunMapper nodeRunMapper,
            ObjectMapper objectMapper,
            ModelInvocationMapper modelInvocationMapper
    ) {
        this.nodeRunMapper = nodeRunMapper;
        this.objectMapper = objectMapper;
        this.modelInvocationMapper = modelInvocationMapper;
    }

    public void assemble(CompiledWorkflow graph, WorkflowNodeRun target) {
        ObjectNode input = objectInput(target.getInputJson());
        Map<String, WorkflowNodeRun> latest = latestRuns(target.getWorkflowRunId());
        Set<String> ancestorIds = ancestors(graph, target.getNodeId());
        for (String ancestor : ancestorIds) {
            WorkflowNodeRun source = latest.get(ancestor);
            if (source == null || !"SUCCEEDED".equals(source.getStatus()) || source.getOutputJson() == null) {
                continue;
            }
            input.set(inputField(graph.nodes().get(ancestor)), json(source.getOutputJson()));
        }
        if ("evaluator".equals(target.getNodeId())) {
            input.set("records", trustedRecords(latest, ancestorIds));
            input.set("model_invocations", trustedModelInvocations(target.getWorkflowRunId()));
            input.putArray("generated_files");
        }
        String assembled = input.toString();
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, target.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.PENDING.name())
                .set(WorkflowNodeRun::getInputJson, assembled));
        if (updated == 1) {
            target.setInputJson(assembled);
        }
    }

    private Map<String, WorkflowNodeRun> latestRuns(long runId) {
        Map<String, WorkflowNodeRun> latest = new LinkedHashMap<>();
        for (WorkflowNodeRun candidate : nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                        .orderByDesc(WorkflowNodeRun::getRevision)
                        .orderByDesc(WorkflowNodeRun::getAttempt))) {
            latest.putIfAbsent(candidate.getNodeId(), candidate);
        }
        return latest;
    }

    private ArrayNode trustedRecords(
            Map<String, WorkflowNodeRun> latest,
            Set<String> ancestorIds
    ) {
        ArrayNode records = objectMapper.createArrayNode();
        for (String nodeId : ancestorIds) {
            WorkflowNodeRun run = latest.get(nodeId);
            if (run == null) {
                continue;
            }
            ObjectNode record = records.addObject();
            record.put("node_name", run.getNodeId());
            record.put("execution_id", run.getExecutionId());
            record.put("revision", run.getRevision());
            record.put("attempt", run.getAttempt());
            record.put("status", run.getStatus());
            if (run.getDurationMs() != null) {
                record.put("duration_ms", run.getDurationMs());
            }
            if (run.getErrorCode() != null) {
                record.put("error_code", run.getErrorCode());
            }
        }
        return records;
    }

    private ArrayNode trustedModelInvocations(long workflowRunId) {
        ArrayNode values = objectMapper.createArrayNode();
        for (ModelInvocation invocation : modelInvocationMapper.selectList(
                new LambdaQueryWrapper<ModelInvocation>()
                        .eq(ModelInvocation::getWorkflowRunId, workflowRunId)
                        .orderByAsc(ModelInvocation::getId))) {
            ObjectNode value = values.addObject();
            value.put("invocation_id", invocation.getId());
            value.put("call_id", invocation.getCallId());
            value.put("call_type", invocation.getCallType());
            value.put("execution_id", invocation.getExecutionId());
            value.put("call_sequence", invocation.getCallSequence());
            value.put("attempt", invocation.getAttempt());
            value.put("node_run_id", invocation.getWorkflowNodeRunId());
            value.put("provider", invocation.getProviderKey());
            value.put("model", invocation.getModelName());
            value.put("status", invocation.getStatus());
            value.put("route_key", invocation.getRouteKey());
            value.put("route_reason", invocation.getRouteReason());
            value.put("fallback_used", Boolean.TRUE.equals(invocation.getFallbackUsed()));
            value.put("model_call_count", invocation.getCallCount());
            value.put("input_tokens", invocation.getInputTokens());
            value.put("output_tokens", invocation.getOutputTokens());
            value.put("cache_tokens", invocation.getCacheTokens());
            value.put("estimated_cost", invocation.getEstimatedCost());
            value.put("prompt_version", invocation.getPromptVersion());
            value.put("prompt_checksum", invocation.getPromptChecksum());
            value.put("schema_version", invocation.getSchemaVersion());
            value.put("contract_hash", invocation.getContractHash());
            value.put("normalized_params_hash", invocation.getNormalizedParamsHash());
            value.put("result_hash", invocation.getResultHash());
            value.put("reserved_input_tokens", invocation.getReservedInputTokens());
            value.put("reserved_output_tokens", invocation.getReservedOutputTokens());
            value.put("reserved_cost", invocation.getReservedCost());
            value.put("settlement_delta_tokens", invocation.getSettlementDeltaTokens());
            value.put("settlement_delta_cost", invocation.getSettlementDeltaCost());
            value.put("error_code", invocation.getErrorCode());
            value.put("error_message", invocation.getErrorMessage());
            value.put("duration_ms", invocation.getDurationMs());
            value.put("deadline_epoch_ms", invocation.getDeadlineEpochMs());
            value.put("idempotency_key", invocation.getIdempotencyKey());
            value.put("tool_name", invocation.getToolName());
            value.put("tool_version", invocation.getToolVersion());
            value.put("permission_policy", invocation.getPermissionPolicy());
            value.put("reference_sources_json", invocation.getReferenceSourcesJson());
        }
        return values;
    }

    private Set<String> ancestors(CompiledWorkflow graph, String nodeId) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(graph.predecessors().getOrDefault(nodeId, java.util.List.of()));
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (result.add(current)) {
                queue.addAll(graph.predecessors().getOrDefault(current, java.util.List.of()));
            }
        }
        return result;
    }

    private String inputField(com.autospec.workflow.spec.WorkflowNodeDocument node) {
        if (node.artifactType() == null || node.artifactType().isBlank()) {
            return node.nodeId();
        }
        return switch (node.artifactType()) {
            case "PRD" -> "prd";
            case "ARCHITECTURE_DESIGN" -> "architecture_design";
            case "BACKEND_DESIGN" -> "backend_design";
            case "FRONTEND_SKELETON" -> "frontend_skeleton";
            case "REVIEW_REPORT" -> "review_report";
            case "EVALUATION_REPORT" -> "evaluation_report";
            default -> node.nodeId();
        };
    }

    private ObjectNode objectInput(String value) {
        JsonNode parsed = json(value == null || value.isBlank() ? "{}" : value);
        if (!parsed.isObject()) {
            throw new IllegalArgumentException("workflow node input must be a JSON object");
        }
        return (ObjectNode) parsed.deepCopy();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid workflow node input JSON", exception);
        }
    }
}
