package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    public WorkflowNodeInputAssembler(
            WorkflowNodeRunMapper nodeRunMapper,
            ObjectMapper objectMapper
    ) {
        this.nodeRunMapper = nodeRunMapper;
        this.objectMapper = objectMapper;
    }

    public void assemble(CompiledWorkflow graph, WorkflowNodeRun target) {
        ObjectNode input = objectInput(target.getInputJson());
        Map<String, WorkflowNodeRun> latest = latestRuns(target.getWorkflowRunId());
        for (String ancestor : ancestors(graph, target.getNodeId())) {
            WorkflowNodeRun source = latest.get(ancestor);
            if (source == null || !"SUCCEEDED".equals(source.getStatus()) || source.getOutputJson() == null) {
                continue;
            }
            input.set(inputField(graph.nodes().get(ancestor)), json(source.getOutputJson()));
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
