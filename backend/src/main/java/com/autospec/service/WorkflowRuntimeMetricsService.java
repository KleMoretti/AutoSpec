package com.autospec.service;

import com.autospec.dto.WorkflowRuntimeMetricsResponse;
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
                estimatedCost,
                safe(run.getAcceptedDuplicateEventCount())
        );
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
