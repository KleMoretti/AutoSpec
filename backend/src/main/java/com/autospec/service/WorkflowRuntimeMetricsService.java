package com.autospec.service;

import com.autospec.dto.WorkflowRuntimeMetricsResponse;
import com.autospec.dto.ModelUsageResponse;
import com.autospec.dto.WorkflowMetricSliceResponse;
import com.autospec.dto.WorkflowNodeMetricsResponse;
import com.autospec.entity.ModelInvocation;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WorkflowRuntimeMetricsService {
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ModelInvocationMapper modelInvocationMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public WorkflowRuntimeMetricsService(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ModelInvocationMapper modelInvocationMapper,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.modelInvocationMapper = modelInvocationMapper;
        this.objectMapper = objectMapper;
    }

    public WorkflowRuntimeMetricsService(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ModelInvocationMapper modelInvocationMapper
    ) {
        this(runMapper, nodeRunMapper, modelInvocationMapper, new ObjectMapper());
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
                aggregateUsage(invocations),
                nodeMetrics(nodes, invocations),
                versionSlices(nodes, invocations)
        );
    }

    private List<WorkflowNodeMetricsResponse> nodeMetrics(
            List<WorkflowNodeRun> nodes,
            List<ModelInvocation> invocations
    ) {
        Map<Long, List<ModelInvocation>> byNodeRun = invocations.stream()
                .filter(invocation -> invocation.getWorkflowNodeRunId() != null)
                .collect(Collectors.groupingBy(
                        ModelInvocation::getWorkflowNodeRunId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<String, List<WorkflowNodeRun>> byNode = nodes.stream()
                .collect(Collectors.groupingBy(
                        node -> node.getNodeId() == null ? "unknown" : node.getNodeId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        return byNode.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> nodeMetric(entry.getKey(), entry.getValue(), byNodeRun))
                .toList();
    }

    private WorkflowNodeMetricsResponse nodeMetric(
            String nodeId,
            List<WorkflowNodeRun> nodes,
            Map<Long, List<ModelInvocation>> byNodeRun
    ) {
        List<ModelInvocation> nodeInvocations = nodes.stream()
                .filter(node -> node.getId() != null)
                .flatMap(node -> byNodeRun.getOrDefault(node.getId(), List.of()).stream())
                .toList();
        List<Long> queueTimes = nodes.stream()
                .map(this::queueTime)
                .filter(value -> value >= 0)
                .toList();
        List<Long> durations = nodes.stream()
                .map(WorkflowNodeRun::getDurationMs)
                .filter(value -> value != null && value >= 0)
                .map(Integer::longValue)
                .toList();
        long tokenCount = nodeInvocations.isEmpty()
                ? nodes.stream().mapToLong(node -> safeLong(node.getActualInputTokens())
                        + safeLong(node.getActualOutputTokens())).sum()
                : nodeInvocations.stream().mapToLong(invocation ->
                        safeLong(invocation.getInputTokens()) + safeLong(invocation.getOutputTokens())).sum();
        BigDecimal cost = nodeInvocations.isEmpty()
                ? nodes.stream().map(WorkflowNodeRun::getActualCost)
                        .filter(value -> value != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : nodeInvocations.stream().map(ModelInvocation::getEstimatedCost)
                        .filter(value -> value != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        int modelCalls = nodeInvocations.stream()
                .filter(invocation -> !"TOOL".equals(invocation.getCallType()))
                .mapToInt(invocation -> invocation.getCallCount() == null
                        ? 1 : invocation.getCallCount())
                .sum();
        int toolCalls = (int) nodeInvocations.stream()
                .filter(invocation -> "TOOL".equals(invocation.getCallType()))
                .count();
        Map<String, Integer> routes = new LinkedHashMap<>();
        nodeInvocations.stream()
                .filter(invocation -> !"TOOL".equals(invocation.getCallType()))
                .forEach(invocation -> routes.merge(
                        textOrUnknown(invocation.getRouteKey()), 1, Integer::sum));
        WorkflowNodeRun latest = nodes.stream()
                .max(Comparator.comparing(node -> node.getId() == null ? 0L : node.getId()))
                .orElse(nodes.get(0));
        String handlerKey = latest.getHandlerKey();
        String handlerVersion = latest.getHandlerVersion();
        return new WorkflowNodeMetricsResponse(
                nodeId,
                handlerKey,
                handlerVersion,
                nodes.size(),
                (int) nodes.stream().filter(node -> "SUCCEEDED".equals(node.getStatus())).count(),
                (int) nodes.stream().filter(node -> "FAILED".equals(node.getStatus())).count(),
                (int) nodes.stream().filter(node -> safe(node.getAttempt()) > 1).count(),
                percentile(queueTimes, 0.50),
                percentile(queueTimes, 0.95),
                percentile(durations, 0.50),
                percentile(durations, 0.95),
                tokenCount,
                cost,
                modelCalls,
                toolCalls,
                routes,
                latest.getStatus()
        );
    }

    private List<WorkflowMetricSliceResponse> versionSlices(
            List<WorkflowNodeRun> nodes,
            List<ModelInvocation> invocations
    ) {
        Map<String, SliceAccumulator> slices = new LinkedHashMap<>();
        for (ModelInvocation invocation : invocations) {
            String callType = "TOOL".equals(invocation.getCallType()) ? "TOOL" : "MODEL";
            String key = "TOOL".equals(callType)
                    ? textOrUnknown(invocation.getToolName())
                    : textOrUnknown(invocation.getProviderKey());
            String version = "TOOL".equals(callType)
                    ? textOrUnknown(invocation.getToolVersion())
                    : textOrUnknown(invocation.getModelName());
            slices.computeIfAbsent(sliceKey(callType, key, version), ignored ->
                    new SliceAccumulator(callType, key, version)).add(invocation);
            if (!"TOOL".equals(callType)) {
                String promptKey = textOrUnknown(invocation.getPromptKey());
                String promptVersion = textOrUnknown(invocation.getPromptVersion());
                slices.computeIfAbsent(sliceKey("PROMPT", promptKey, promptVersion), ignored ->
                        new SliceAccumulator("PROMPT", promptKey, promptVersion)).add(invocation);
            }
        }
        addRetrieverSlices(slices, nodes);
        return slices.values().stream().map(SliceAccumulator::toResponse).toList();
    }

    private void addRetrieverSlices(
            Map<String, SliceAccumulator> slices,
            List<WorkflowNodeRun> nodes
    ) {
        for (WorkflowNodeRun node : nodes) {
            JsonNode input = json(node.getInputJson());
            JsonNode retrievalTrace = input.path("retrieval_trace");
            Set<String> seen = new HashSet<>();
            if (retrievalTrace.isObject()) {
                String strategy = textOrUnknown(retrievalTrace.path("retriever_version").asText(null));
                String embedding = textOrUnknown(retrievalTrace.path("embedding_version").asText(null));
                String key = sliceKey("RETRIEVER", strategy, embedding);
                seen.add(key);
                SliceAccumulator slice = slices.computeIfAbsent(key, ignored ->
                        new SliceAccumulator("RETRIEVER", strategy, embedding));
                slice.addNode("FAILED".equals(node.getStatus())
                        || retrievalTrace.path("empty_recall").asBoolean(false));
            }
            JsonNode sources = input.path("retrieved_sources");
            if (!sources.isArray()) {
                continue;
            }
            for (JsonNode source : sources) {
                String strategy = textOrUnknown(source.path("retrieval_strategy").asText(null));
                String embedding = textOrUnknown(source.path("embedding_model").asText(null));
                String key = sliceKey("RETRIEVER", strategy, embedding);
                if (seen.add(key)) {
                    SliceAccumulator slice = slices.computeIfAbsent(key, ignored ->
                            new SliceAccumulator("RETRIEVER", strategy, embedding));
                    slice.addNode("FAILED".equals(node.getStatus()));
                }
            }
        }
    }

    private JsonNode json(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String sliceKey(String dimension, String key, String version) {
        return dimension + "\u0000" + key + "\u0000" + version;
    }

    private long queueTime(WorkflowNodeRun node) {
        if (node.getQueuedAt() == null || node.getStartedAt() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(node.getQueuedAt(), node.getStartedAt()).toMillis());
    }

    private long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
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

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private long safeLong(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private String textOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
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

    private static final class SliceAccumulator {
        private final String dimension;
        private final String key;
        private final String version;
        private final List<Long> durations = new ArrayList<>();
        private int invocationCount;
        private int failureCount;
        private long tokenCount;
        private BigDecimal estimatedCost = BigDecimal.ZERO;

        private SliceAccumulator(String dimension, String key, String version) {
            this.dimension = dimension;
            this.key = key;
            this.version = version;
        }

        private void add(ModelInvocation invocation) {
            invocationCount++;
            if ("FAILED".equals(invocation.getStatus())) {
                failureCount++;
            }
            tokenCount += safe(invocation.getInputTokens()) + safe(invocation.getOutputTokens());
            estimatedCost = estimatedCost.add(
                    invocation.getEstimatedCost() == null
                            ? BigDecimal.ZERO
                            : invocation.getEstimatedCost()
            );
            if (invocation.getDurationMs() != null && invocation.getDurationMs() >= 0) {
                durations.add(invocation.getDurationMs().longValue());
            }
        }

        private void addNode(boolean failed) {
            invocationCount++;
            if (failed) {
                failureCount++;
            }
        }

        private WorkflowMetricSliceResponse toResponse() {
            List<Long> sorted = new ArrayList<>(durations);
            sorted.sort(Long::compareTo);
            long p95 = sorted.isEmpty()
                    ? 0
                    : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
            return new WorkflowMetricSliceResponse(
                    dimension,
                    key,
                    version,
                    invocationCount,
                    failureCount,
                    tokenCount,
                    estimatedCost,
                    p95
            );
        }
    }
}
