package com.autospec.workflow.transport;

import com.autospec.entity.ModelInvocation;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class MybatisWorkflowUsageRecorder implements WorkflowUsageRecorder {
    private static final BigDecimal COST_TOLERANCE = new BigDecimal("0.000001");

    private final ModelInvocationMapper invocationMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public MybatisWorkflowUsageRecorder(
            ModelInvocationMapper invocationMapper,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ObjectMapper objectMapper
    ) {
        this.invocationMapper = invocationMapper;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.objectMapper = objectMapper;
    }

    public MybatisWorkflowUsageRecorder(
            ModelInvocationMapper invocationMapper,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper
    ) {
        this(invocationMapper, runMapper, nodeRunMapper, new ObjectMapper());
    }

    @Override
    @Transactional
    public UsageDecision record(WorkflowExecutionEvent event) {
        if (!event.isTerminal()) {
            return UsageDecision.ALLOWED;
        }
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
        if ("RESERVED".equals(node.getBudgetStatus()) && safe(event.protocolVersion()) < 2) {
            failSettlement(
                    run,
                    node,
                    "Protocol downgrade cannot settle a frozen budget reservation"
            );
            return UsageDecision.BUDGET_EXCEEDED;
        }
        if (safe(event.protocolVersion()) >= 2) {
            return settleFrozenReservation(run, node, event);
        }
        return recordLegacyUsage(run, event);
    }

    private UsageDecision settleFrozenReservation(
            WorkflowRun run,
            WorkflowNodeRun node,
            WorkflowExecutionEvent event
    ) {
        long reservedTokens = safeLong(node.getReservedInputTokens())
                + safeLong(node.getReservedOutputTokens());
        BigDecimal reservedCost = safeCost(node.getReservedCost());
        int reservedCalls = safe(node.getReservedModelCalls());
        long actualTokens = (long) safe(event.inputTokens()) + safe(event.outputTokens());
        BigDecimal actualCost = safeCost(event.estimatedCost());
        int actualCalls = safe(event.modelCallCount());

        if (!"RESERVED".equals(node.getBudgetStatus())
                || !event.executionId().equals(node.getBudgetReservationId())
                || !event.executionId().equals(event.budgetReservationId())
                || actualTokens > reservedTokens
                || actualCost.subtract(reservedCost).compareTo(COST_TOLERANCE) > 0
                || actualCalls > reservedCalls
                || !callCostsMatch(event)
                || !callReservationsFit(node, event)) {
            failSettlement(run, node, "Frozen usage exceeded or mismatched its reservation");
            return UsageDecision.BUDGET_EXCEEDED;
        }

        for (WorkflowCallRecord call : event.callRecords()) {
            insertInvocation(run, event, call);
        }
        if (runMapper.settleModelBudget(
                run.getId(),
                reservedTokens,
                reservedCost,
                reservedCalls,
                actualTokens,
                actualCost,
                actualCalls
        ) != 1) {
            throw new IllegalStateException(
                    "Workflow usage reservation could not be settled exactly once"
            );
        }
        LocalDateTime now = LocalDateTime.now();
        int settled = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, node.getId())
                .eq(WorkflowNodeRun::getExecutionId, event.executionId())
                .eq(WorkflowNodeRun::getBudgetReservationId, event.budgetReservationId())
                .eq(WorkflowNodeRun::getBudgetStatus, "RESERVED")
                .set(WorkflowNodeRun::getActualInputTokens, safe(event.inputTokens()))
                .set(WorkflowNodeRun::getActualOutputTokens, safe(event.outputTokens()))
                .set(WorkflowNodeRun::getActualCacheTokens, safe(event.cacheTokens()))
                .set(WorkflowNodeRun::getActualCost, actualCost)
                .set(WorkflowNodeRun::getActualModelCalls, actualCalls)
                .set(WorkflowNodeRun::getActualToolCalls, safe(event.toolCallCount()))
                .set(WorkflowNodeRun::getBudgetStatus, "SETTLED")
                .set(WorkflowNodeRun::getBudgetSettledAt, now)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (settled != 1) {
            throw new IllegalStateException("Node usage reservation was concurrently settled");
        }
        return wallTimeExceeded(run)
                ? failForWallTime(run, "Maximum wall time exceeded")
                : UsageDecision.ALLOWED;
    }

    private boolean callCostsMatch(WorkflowExecutionEvent event) {
        BigDecimal recorded = event.callRecords().stream()
                .map(WorkflowCallRecord::estimatedCost)
                .map(this::safeCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return recorded.subtract(safeCost(event.estimatedCost())).abs()
                .compareTo(COST_TOLERANCE) <= 0;
    }

    private boolean callReservationsFit(
            WorkflowNodeRun node,
            WorkflowExecutionEvent event
    ) {
        long input = event.callRecords().stream()
                .filter(WorkflowCallRecord::isModelCall)
                .mapToLong(call -> safe(call.reservedInputTokens()))
                .sum();
        long output = event.callRecords().stream()
                .filter(WorkflowCallRecord::isModelCall)
                .mapToLong(call -> safe(call.reservedOutputTokens()))
                .sum();
        BigDecimal cost = event.callRecords().stream()
                .map(WorkflowCallRecord::reservedCost)
                .map(this::safeCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return input <= safeLong(node.getReservedInputTokens())
                && output <= safeLong(node.getReservedOutputTokens())
                && cost.subtract(safeCost(node.getReservedCost()))
                .compareTo(COST_TOLERANCE) <= 0;
    }

    private UsageDecision recordLegacyUsage(WorkflowRun run, WorkflowExecutionEvent event) {
        int calls = safe(event.modelCallCount());
        long tokens = (long) safe(event.inputTokens()) + safe(event.outputTokens());
        BigDecimal cost = safeCost(event.estimatedCost());
        if (calls > 0) {
            insertLegacyInvocation(run, event, calls, cost);
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
            WorkflowCallRecord call
    ) {
        ModelInvocation invocation = baseInvocation(run, event);
        invocation.setCallId(call.callId());
        invocation.setCallType(call.callType());
        invocation.setExecutionId(event.executionId());
        invocation.setCallSequence(call.callSequence());
        invocation.setAttempt(call.attempt());
        invocation.setProviderKey(defaultValue(call.providerKey(), "unknown"));
        invocation.setModelName(defaultValue(call.modelName(), "unknown"));
        invocation.setPromptKey(call.promptKey());
        invocation.setPromptVersion(call.promptVersion());
        invocation.setPromptChecksum(call.promptChecksum());
        invocation.setSchemaVersion(call.schemaVersion());
        invocation.setContractHash(defaultValue(call.contractHash(), event.contractHash()));
        invocation.setRouteKey(call.routeKey());
        invocation.setRouteReason(call.routeReason());
        invocation.setFallbackUsed(Boolean.TRUE.equals(call.fallbackUsed()));
        invocation.setStatus(call.status());
        invocation.setDurationMs(safe(call.durationMs()));
        invocation.setInputTokens(safe(call.inputTokens()));
        invocation.setOutputTokens(safe(call.outputTokens()));
        invocation.setCacheTokens(safe(call.cacheTokens()));
        invocation.setCallCount(call.isModelCall() ? 1 : 0);
        invocation.setEstimatedCost(safeCost(call.estimatedCost()));
        invocation.setReservedInputTokens(safe(call.reservedInputTokens()));
        invocation.setReservedOutputTokens(safe(call.reservedOutputTokens()));
        invocation.setReservedCost(safeCost(call.reservedCost()));
        invocation.setSettlementDeltaTokens(
                safe(call.reservedInputTokens())
                        + safe(call.reservedOutputTokens())
                        - safe(call.inputTokens())
                        - safe(call.outputTokens())
        );
        invocation.setSettlementDeltaCost(
                safeCost(call.reservedCost()).subtract(safeCost(call.estimatedCost()))
        );
        invocation.setNormalizedParamsHash(call.normalizedParamsHash());
        invocation.setResultHash(call.resultHash());
        invocation.setErrorCode(call.errorCode());
        invocation.setErrorMessage(call.errorMessage());
        invocation.setDeadlineEpochMs(call.deadlineEpochMs());
        invocation.setIdempotencyKey(call.idempotencyKey());
        invocation.setToolName(call.toolName());
        invocation.setToolVersion(call.toolVersion());
        invocation.setPermissionPolicy(call.permissionPolicy());
        invocation.setReferenceSourcesJson(serialize(call.referenceSources()));
        invocation.setRedactedParamsJson(
                call.redactedParams() == null ? null : call.redactedParams().toString()
        );
        invocationMapper.insert(invocation);
    }

    private void insertLegacyInvocation(
            WorkflowRun run,
            WorkflowExecutionEvent event,
            int calls,
            BigDecimal cost
    ) {
        ModelInvocation invocation = baseInvocation(run, event);
        invocation.setProviderKey(defaultValue(event.providerKey(), "unknown"));
        invocation.setModelName(defaultValue(event.modelName(), "unknown"));
        invocation.setPromptKey(event.promptKey());
        invocation.setPromptVersion(event.promptVersion());
        invocation.setPromptChecksum(event.promptChecksum());
        invocation.setContractHash(event.contractHash());
        invocation.setRouteKey(event.routeKey());
        invocation.setRouteReason(event.routeReason());
        invocation.setFallbackUsed(Boolean.TRUE.equals(event.fallbackUsed()));
        invocation.setStatus("NODE_SUCCEEDED".equals(event.eventType()) ? "SUCCEEDED" : "FAILED");
        invocation.setDurationMs(event.durationMs() == null ? 0 : event.durationMs());
        invocation.setInputTokens(safe(event.inputTokens()));
        invocation.setOutputTokens(safe(event.outputTokens()));
        invocation.setCacheTokens(safe(event.cacheTokens()));
        invocation.setCallCount(calls);
        invocation.setEstimatedCost(cost);
        invocation.setErrorMessage(event.errorMessage());
        invocationMapper.insert(invocation);
    }

    private ModelInvocation baseInvocation(WorkflowRun run, WorkflowExecutionEvent event) {
        ModelInvocation invocation = new ModelInvocation();
        invocation.setProjectId(run.getProjectId());
        invocation.setWorkflowRunId(run.getId());
        invocation.setWorkflowNodeRunId(event.nodeRunId());
        invocation.setCorrelationId(event.correlationId());
        invocation.setAgentNode(event.nodeId());
        invocation.setContextManifestJson(
                event.contextManifest() == null ? null : event.contextManifest().toString()
        );
        invocation.setCreatedAt(LocalDateTime.now());
        return invocation;
    }

    private boolean wallTimeExceeded(WorkflowRun run) {
        return run.getMaxWallTimeMs() != null
                && run.getStartedAt() != null
                && Duration.between(run.getStartedAt(), LocalDateTime.now()).toMillis()
                > run.getMaxWallTimeMs();
    }

    private UsageDecision failForWallTime(WorkflowRun run, String message) {
        failRun(run, 0, BigDecimal.ZERO, 0, message);
        return UsageDecision.BUDGET_EXCEEDED;
    }

    private void failSettlement(WorkflowRun run, WorkflowNodeRun node, String message) {
        LocalDateTime now = LocalDateTime.now();
        runMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, run.getId())
                .eq(WorkflowRun::getStatus, "RUNNING")
                .set(WorkflowRun::getStatus, "FAILED")
                .set(WorkflowRun::getResponseStatus, "BUDGET_SETTLEMENT_FAILED")
                .set(WorkflowRun::getErrorMessage, message)
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now));
        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, node.getId())
                .set(WorkflowNodeRun::getStatus, "FAILED")
                .set(WorkflowNodeRun::getErrorCode, "BUDGET_SETTLEMENT_FAILED")
                .set(WorkflowNodeRun::getErrorMessage, message)
                .set(WorkflowNodeRun::getBudgetStatus, "SETTLEMENT_FAILED")
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, run.getId())
                .ne(WorkflowNodeRun::getId, node.getId())
                .in(WorkflowNodeRun::getStatus,
                        "PENDING", "QUEUED", "RUNNING", "RETRY_WAIT", "FALLBACK_READY")
                .set(WorkflowNodeRun::getStatus, "CANCELLED")
                .set(WorkflowNodeRun::getErrorCode, "BUDGET_SETTLEMENT_FAILED")
                .set(WorkflowNodeRun::getErrorMessage, message)
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getUpdatedAt, now));
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

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize call ledger metadata", exception);
        }
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
