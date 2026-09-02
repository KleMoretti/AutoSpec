package com.autospec.service;

import com.autospec.dto.WorkflowRuntimeMetricsResponse;
import com.autospec.dto.ModelUsageResponse;
import com.autospec.entity.ModelInvocation;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WorkflowRuntimeMetricsService {
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ModelInvocationMapper modelInvocationMapper;

    public WorkflowRuntimeMetricsService(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ModelInvocationMapper modelInvocationMapper
    ) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.modelInvocationMapper = modelInvocationMapper;
    }

    public WorkflowRuntimeMetricsResponse metrics(long workflowRunId) {
        WorkflowRun run = runMapper.selectById(workflowRunId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        List<WorkflowNodeRun> nodes = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, workflowRunId)
        );
        List<ModelInvocation> invocations = modelInvocationMapper.selectList(
                new LambdaQueryWrapper<ModelInvocation>()
                        .eq(ModelInvocation::getWorkflowRunId, workflowRunId)
        );

        long queueTimeMs = nodes.stream()
                .filter(node -> node.getQueuedAt() != null && node.getStartedAt() != null)
                .mapToLong(node -> Math.max(0, Duration.between(
                        node.getQueuedAt(), node.getStartedAt()).toMillis()))
                .sum();
        long executionDurationMs = nodes.stream()
                .map(WorkflowNodeRun::getDurationMs)
                .filter(duration -> duration != null && duration >= 0)
                .mapToLong(Integer::longValue)
                .sum();
        int retryCount = nodes.stream()
                .map(WorkflowNodeRun::getAttempt)
                .filter(attempt -> attempt != null && attempt > 1)
                .mapToInt(attempt -> 1)
                .sum();
        int recoveryCount = (int) nodes.stream()
                .filter(node -> "ORPHANED".equals(node.getStatus()))
                .count();
        long tokenCount = invocations.stream()
                .mapToLong(invocation -> safe(invocation.getInputTokens()) + safe(invocation.getOutputTokens()))
                .sum();
        long cacheTokenCount = invocations.stream()
                .mapToLong(invocation -> safe(invocation.getCacheTokens()))
                .sum();
        int modelCallCount = invocations.stream()
                .mapToInt(invocation -> invocation.getCallCount() == null
                        ? 1
                        : invocation.getCallCount())
                .sum();
        BigDecimal estimatedCost = invocations.stream()
                .map(ModelInvocation::getEstimatedCost)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new WorkflowRuntimeMetricsResponse(
                workflowRunId,
                nodes.size(),
                queueTimeMs,
                executionDurationMs,
                retryCount,
                recoveryCount,
                tokenCount,
                cacheTokenCount,
                estimatedCost,
                modelCallCount,
                safe(run.getAcceptedDuplicateEventCount()),
                run.getQualityProfile(),
                run.getMaxTokens(),
                run.getMaxCost(),
                run.getMaxModelCalls(),
                run.getMaxWallTimeMs(),
                run.getReservedTokens(),
                run.getReservedCost(),
                run.getReservedModelCalls(),
                remaining(run.getMaxTokens(), run.getConsumedTokens(), run.getReservedTokens()),
                remaining(run.getMaxCost(), run.getConsumedCost(), run.getReservedCost()),
                remaining(run.getMaxModelCalls(), run.getModelCallCount(), run.getReservedModelCalls()),
                aggregateUsage(invocations)
        );
    }

    private List<ModelUsageResponse> aggregateUsage(List<ModelInvocation> invocations) {
        Map<String, UsageAccumulator> grouped = new LinkedHashMap<>();
        for (ModelInvocation invocation : invocations) {
            String provider = invocation.getProviderKey() == null ? "unknown" : invocation.getProviderKey();
            String model = invocation.getModelName() == null ? "unknown" : invocation.getModelName();
            UsageAccumulator usage = grouped.computeIfAbsent(
                    provider + "\u0000" + model,
                    ignored -> new UsageAccumulator(provider, model)
            );
            usage.invocationCount++;
            usage.modelCallCount += invocation.getCallCount() == null ? 1 : invocation.getCallCount();
            usage.inputTokens += safe(invocation.getInputTokens());
            usage.outputTokens += safe(invocation.getOutputTokens());
            usage.cacheTokens += safe(invocation.getCacheTokens());
            usage.estimatedCost = usage.estimatedCost.add(
                    invocation.getEstimatedCost() == null
                            ? BigDecimal.ZERO
                            : invocation.getEstimatedCost()
            );
        }
        return grouped.values().stream()
                .map(usage -> new ModelUsageResponse(
                        usage.provider,
                        usage.model,
                        usage.invocationCount,
                        usage.modelCallCount,
                        usage.inputTokens,
                        usage.outputTokens,
                        usage.cacheTokens,
                        usage.estimatedCost
                ))
                .toList();
    }

    private Long remaining(Long maximum, Long consumed, Long reserved) {
        return maximum == null
                ? null
                : Math.max(0, maximum - (consumed == null ? 0 : consumed)
                - (reserved == null ? 0 : reserved));
    }

    private Integer remaining(Integer maximum, Integer consumed, Integer reserved) {
        return maximum == null
                ? null
                : Math.max(0, maximum - safe(consumed) - safe(reserved));
    }

    private BigDecimal remaining(
            BigDecimal maximum,
            BigDecimal consumed,
            BigDecimal reserved
    ) {
        if (maximum == null) {
            return null;
        }
        return maximum
                .subtract(consumed == null ? BigDecimal.ZERO : consumed)
                .subtract(reserved == null ? BigDecimal.ZERO : reserved)
                .max(BigDecimal.ZERO);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private static final class UsageAccumulator {
        private final String provider;
        private final String model;
        private int invocationCount;
        private int modelCallCount;
        private long inputTokens;
        private long outputTokens;
        private long cacheTokens;
        private BigDecimal estimatedCost = BigDecimal.ZERO;

        private UsageAccumulator(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }
    }
}
