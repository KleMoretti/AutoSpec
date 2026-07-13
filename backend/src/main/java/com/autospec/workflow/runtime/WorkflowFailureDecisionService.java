package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class WorkflowFailureDecisionService {
    private final WorkflowRunMapper workflowRunMapper;
    private final ObjectMapper objectMapper;
    private final RetryPolicyEvaluator evaluator;

    public WorkflowFailureDecisionService(
            WorkflowRunMapper workflowRunMapper,
            ObjectMapper objectMapper,
            RetryPolicyEvaluator evaluator
    ) {
        this.workflowRunMapper = workflowRunMapper;
        this.objectMapper = objectMapper;
        this.evaluator = evaluator;
    }

    public RetryPolicyEvaluator.Decision decide(
            WorkflowNodeRun nodeRun,
            String errorCode,
            LocalDateTime failedAt
    ) {
        try {
            WorkflowRun workflowRun = workflowRunMapper.selectById(nodeRun.getWorkflowRunId());
            if (workflowRun == null || workflowRun.getWorkflowSnapshotJson() == null) {
                return fail();
            }
            JsonNode nodeSpec = findNode(
                    objectMapper.readTree(workflowRun.getWorkflowSnapshotJson()), nodeRun.getNodeId()
            );
            if (nodeSpec == null) {
                return fail();
            }
            return evaluator.evaluate(nodeRun.getAttempt(), errorCode, failedAt, policy(nodeSpec));
        } catch (RuntimeException | java.io.IOException exception) {
            return fail();
        }
    }

    private JsonNode findNode(JsonNode snapshot, String nodeId) {
        JsonNode nodes = snapshot.path("nodes");
        if (!nodes.isArray()) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (nodeId.equals(node.path("node_id").asText())) {
                return node;
            }
        }
        return null;
    }

    private RetryPolicyEvaluator.Policy policy(JsonNode nodeSpec) {
        JsonNode retry = nodeSpec.path("retry_policy");
        Set<String> retryableErrors = new LinkedHashSet<>();
        retry.path("retryable_errors").forEach(value -> retryableErrors.add(value.asText()));
        if (retry.path("retry_on_validation_error").asBoolean(false)) {
            retryableErrors.add("VALIDATION_ERROR");
            retryableErrors.add("OUTPUT_SCHEMA_ERROR");
        }

        JsonNode fallback = nodeSpec.path("fallback");
        String fallbackHandler = fallback.path("enabled").asBoolean(false)
                ? textOrNull(fallback.path("handler"))
                : null;
        return new RetryPolicyEvaluator.Policy(
                retry.path("max_attempts").asInt(1),
                retryableErrors,
                Duration.ofMillis(retry.path("initial_delay_ms").asLong(1000)),
                Duration.ofMillis(retry.path("max_delay_ms").asLong(10000)),
                retry.path("multiplier").asDouble(2.0),
                fallbackHandler
        );
    }

    private String textOrNull(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private RetryPolicyEvaluator.Decision fail() {
        return new RetryPolicyEvaluator.Decision(RetryPolicyEvaluator.Action.FAIL, null, null);
    }
}
