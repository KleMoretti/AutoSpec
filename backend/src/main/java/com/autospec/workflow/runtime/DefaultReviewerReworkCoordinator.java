package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultReviewerReworkCoordinator implements ReviewerReworkCoordinator {
    private final ReworkPlanExecutionService executionService;
    private final ObjectMapper objectMapper;

    public DefaultReviewerReworkCoordinator(
            ReworkPlanExecutionService executionService,
            ObjectMapper objectMapper
    ) {
        this.executionService = executionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean applyIfRequested(WorkflowNodeRun reviewerNode, String outputJson) {
        JsonNode output = parse(outputJson);
        if (!"REWORK".equals(output.path("decision").asText())) {
            return false;
        }
        List<String> targets = new ArrayList<>();
        JsonNode routes = output.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            throw new IllegalArgumentException("Reviewer REWORK decision requires routes");
        }
        routes.forEach(route -> {
            String target = route.path("target_node").asText();
            if (target.isBlank()) {
                throw new IllegalArgumentException("Reviewer rework route requires target_node");
            }
            targets.add(target);
        });
        executionService.execute(
                reviewerNode.getWorkflowRunId(),
                reviewerNode.getNodeId(),
                targets.stream().distinct().toList()
        );
        return true;
    }

    private JsonNode parse(String outputJson) {
        try {
            return objectMapper.readTree(outputJson == null ? "{}" : outputJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid Reviewer output JSON", exception);
        }
    }
}
