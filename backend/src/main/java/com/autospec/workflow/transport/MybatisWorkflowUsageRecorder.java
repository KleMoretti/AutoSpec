package com.autospec.workflow.transport;

import com.autospec.entity.ModelInvocation;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class MybatisWorkflowUsageRecorder implements WorkflowUsageRecorder {
    private final ModelInvocationMapper invocationMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;

    public MybatisWorkflowUsageRecorder(
            ModelInvocationMapper invocationMapper,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper
    ) {
        this.invocationMapper = invocationMapper;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
    }

    @Override
    public UsageDecision record(WorkflowExecutionEvent event) {
        WorkflowRun run = runMapper.selectById(event.workflowRunId());
        if (run == null || !"RUNNING".equals(run.getStatus())) {
            return UsageDecision.ALLOWED;
        }
        WorkflowNodeRun node = nodeRunMapper.selectById(event.nodeRunId());
        if (node == null
                || !event.workflowRunId().equals(node.getWorkflowRunId())
                || !event.executionId().equals(node.getExecutionId())
                || !java.util.Set.of("QUEUED", "RUNNING").contains(node.getStatus())) {
            return UsageDecision.ALLOWED;
        }
        int calls = safe(event.modelCallCount());
        long tokens = (long) safe(event.inputTokens()) + safe(event.outputTokens());
        BigDecimal cost = event.estimatedCost() == null
                ? BigDecimal.ZERO
                : event.estimatedCost();

        if (calls > 0) {
            insertInvocation(run, event, calls, cost);
        }

        if (wallTimeExceeded(run)) {
            failRun(run, tokens, cost, calls, "Maximum wall time exceeded");
            return UsageDecision.BUDGET_EXCEEDED;
        }
        if (tokens == 0 && cost.signum() == 0 && calls == 0) {
            return UsageDecision.ALLOWED;
        }
        if (runMapper.reserveModelUsage(run.getId(), tokens, cost, calls) == 1) {
            return UsageDecision.ALLOWED;
        }

        WorkflowRun refreshed = runMapper.selectById(run.getId());
        if (refreshed != null && "RUNNING".equals(refreshed.getStatus())) {
            failRun(refreshed, tokens, cost, calls, exceededMessage(refreshed, tokens, cost, calls));
            return UsageDecision.BUDGET_EXCEEDED;
        }
        return UsageDecision.ALLOWED;
    }

    private void insertInvocation(
            WorkflowRun run,
            WorkflowExecutionEvent event,
            int calls,
            BigDecimal cost
    ) {
        ModelInvocation invocation = new ModelInvocation();
        invocation.setProjectId(run.getProjectId());
        invocation.setWorkflowRunId(run.getId());
        invocation.setWorkflowNodeRunId(event.nodeRunId());
        invocation.setCorrelationId(event.correlationId());
        invocation.setProviderKey(defaultValue(event.providerKey(), "unknown"));
        invocation.setModelName(defaultValue(event.modelName(), "unknown"));
        invocation.setAgentNode(event.nodeId());
        invocation.setPromptKey(event.promptKey());
        invocation.setPromptVersion(event.promptVersion());
        invocation.setPromptChecksum(event.promptChecksum());
        invocation.setContractHash(event.contractHash());
        invocation.setRouteKey(event.routeKey());
        invocation.setRouteReason(event.routeReason());
        invocation.setFallbackUsed(Boolean.TRUE.equals(event.fallbackUsed()));
        invocation.setContextManifestJson(
                event.contextManifest() == null ? null : event.contextManifest().toString()
        );
        invocation.setStatus("NODE_SUCCEEDED".equals(event.eventType()) ? "SUCCEEDED" : "FAILED");
        invocation.setDurationMs(event.durationMs() == null ? 0 : event.durationMs());
        invocation.setInputTokens(safe(event.inputTokens()));
        invocation.setOutputTokens(safe(event.outputTokens()));
        invocation.setCacheTokens(safe(event.cacheTokens()));
        invocation.setCallCount(calls);
        invocation.setEstimatedCost(cost);
        invocation.setErrorMessage(event.errorMessage());
        invocation.setCreatedAt(LocalDateTime.now());
        invocationMapper.insert(invocation);
    }

    private boolean wallTimeExceeded(WorkflowRun run) {
        return run.getMaxWallTimeMs() != null
                && run.getStartedAt() != null
                && Duration.between(run.getStartedAt(), LocalDateTime.now()).toMillis()
                > run.getMaxWallTimeMs();
    }

    private void failRun(
            WorkflowRun run,
            long tokens,
            BigDecimal cost,
            int calls,
            String message
    ) {
        LocalDateTime now = LocalDateTime.now();
        runMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, run.getId())
                .eq(WorkflowRun::getStatus, "RUNNING")
                .setSql("consumed_tokens = consumed_tokens + " + Math.max(0, tokens))
                .setSql("consumed_cost = consumed_cost + " + cost.max(BigDecimal.ZERO).toPlainString())
                .setSql("model_call_count = model_call_count + " + Math.max(0, calls))
                .set(WorkflowRun::getStatus, "FAILED")
                .set(WorkflowRun::getResponseStatus, "BUDGET_EXCEEDED")
                .set(WorkflowRun::getErrorMessage, message)
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now));
        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, run.getId())
                .in(WorkflowNodeRun::getStatus,
                        "PENDING", "QUEUED", "RUNNING", "RETRY_WAIT", "FALLBACK_READY")
                .set(WorkflowNodeRun::getStatus, "CANCELLED")
                .set(WorkflowNodeRun::getErrorCode, "BUDGET_EXCEEDED")
                .set(WorkflowNodeRun::getErrorMessage, message)
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getUpdatedAt, now));
    }

    private String exceededMessage(
            WorkflowRun run,
            long tokens,
            BigDecimal cost,
            int calls
    ) {
        if (run.getMaxTokens() != null
                && safeLong(run.getConsumedTokens()) + tokens > run.getMaxTokens()) {
            return "Token budget exceeded";
        }
        if (run.getMaxCost() != null
                && safeCost(run.getConsumedCost()).add(cost).compareTo(run.getMaxCost()) > 0) {
            return "Cost budget exceeded";
        }
        if (run.getMaxModelCalls() != null
                && safe(run.getModelCallCount()) + calls > run.getMaxModelCalls()) {
            return "Model call budget exceeded";
        }
        return "Workflow execution budget exceeded";
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private long safeLong(Long value) {
        return value == null ? 0 : value;
    }

    private BigDecimal safeCost(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
