package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.entity.RequirementBaseline;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.RequirementBaselineMapper;
import com.autospec.mapper.ProjectMapper;
import com.autospec.util.ContentHash;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RequirementBaselineService {
    private final RequirementBaselineMapper baselineMapper;
    private final ProjectMapper projectMapper;
    private final ObjectMapper objectMapper;

    public RequirementBaselineService(
            RequirementBaselineMapper baselineMapper,
            ProjectMapper projectMapper,
            ObjectMapper objectMapper
    ) {
        this.baselineMapper = baselineMapper;
        this.projectMapper = projectMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RequirementBaseline freeze(
            WorkflowRun run,
            WorkflowNodeRun pmNode,
            Artifact approvedPrd,
            long userId
    ) {
        if (run == null || pmNode == null || approvedPrd == null
                || run.getId() == null || approvedPrd.getId() == null
                || !"PRD".equals(approvedPrd.getType())
                || !"APPROVED".equals(approvedPrd.getStatus())) {
            throw new IllegalArgumentException("an approved PRD is required to freeze a requirement baseline");
        }
        RequirementBaseline existing = findForRun(run.getId());
        if (existing != null) {
            if (!approvedPrd.getId().equals(existing.getPrdArtifactId())) {
                throw new IllegalStateException("requirement baseline is already frozen for this workflow run");
            }
            return existing;
        }

        JsonNode input = object(pmNode.getInputJson());
        JsonNode clarificationContext = input.path("clarification_context");
        JsonNode prd = prdPayload(approvedPrd.getContent());
        ObjectNode stable = objectMapper.createObjectNode();
        stable.put("project_id", run.getProjectId());
        stable.put("workflow_run_id", run.getId());
        stable.put("version", 1);
        stable.put("original_requirement", run.getProjectId() == null
                ? ""
                : originalRequirement(run));
        stable.set("answers", answers(clarificationContext));
        stable.set("assumptions", assumptions(clarificationContext));
        stable.set("conflict_resolutions", resolutions(clarificationContext));
        stable.set("context_conflicts", array(input.path("context_conflicts")));
        stable.set("scope", array(prd.path("business_boundaries")));
        stable.set("constraints", array(prd.path("non_functional_requirements")));
        stable.put("prd_content_hash", approvedPrd.getContentHash());

        RequirementBaseline baseline = new RequirementBaseline();
        baseline.setBaselineId("RB-" + run.getId() + "-1");
        baseline.setProjectId(run.getProjectId());
        baseline.setWorkflowRunId(run.getId());
        baseline.setVersion(1);
        baseline.setOriginalRequirement(originalRequirement(run));
        baseline.setAnswersJson(answers(clarificationContext).toString());
        baseline.setAssumptionsJson(assumptions(clarificationContext).toString());
        baseline.setConflictResolutionsJson(resolutions(clarificationContext).toString());
        baseline.setContextConflictsJson(array(input.path("context_conflicts")).toString());
        baseline.setScopeJson(array(prd.path("business_boundaries")).toString());
        baseline.setConstraintsJson(array(prd.path("non_functional_requirements")).toString());
        baseline.setPrdArtifactId(approvedPrd.getId());
        baseline.setPrdVersion(approvedPrd.getVersion());
        baseline.setPrdContentHash(approvedPrd.getContentHash());
        baseline.setConfirmedByUserId(userId);
        baseline.setConfirmedAt(LocalDateTime.now());
        baseline.setContentHash(ContentHash.sha256(stable.toString()));
        baseline.setCreatedAt(LocalDateTime.now());
        baselineMapper.insert(baseline);
        return baseline;
    }

    public RequirementBaseline findForRun(long workflowRunId) {
        return baselineMapper.selectOne(new LambdaQueryWrapper<RequirementBaseline>()
                .eq(RequirementBaseline::getWorkflowRunId, workflowRunId)
                .orderByDesc(RequirementBaseline::getVersion)
                .last("limit 1"));
    }

    public Map<String, Object> inputForRun(long workflowRunId) {
        RequirementBaseline baseline = findForRun(workflowRunId);
        if (baseline == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseline_id", baseline.getBaselineId());
        result.put("version", baseline.getVersion());
        result.put("content_hash", baseline.getContentHash());
        result.put("original_requirement", baseline.getOriginalRequirement());
        result.put("answers", parsedArray(baseline.getAnswersJson()));
        result.put("assumptions", parsedArray(baseline.getAssumptionsJson()));
        result.put("conflict_resolutions", parsedArray(baseline.getConflictResolutionsJson()));
        result.put("context_conflicts", parsedArray(baseline.getContextConflictsJson()));
        result.put("scope", parsedArray(baseline.getScopeJson()));
        result.put("constraints", parsedArray(baseline.getConstraintsJson()));
        result.put("prd_artifact_id", baseline.getPrdArtifactId());
        result.put("prd_version", baseline.getPrdVersion());
        result.put("prd_content_hash", baseline.getPrdContentHash());
        result.put("confirmed_by_user_id", baseline.getConfirmedByUserId());
        result.put("confirmed_at", baseline.getConfirmedAt());
        return result;
    }

    private String originalRequirement(WorkflowRun run) {
        if (run.getProjectId() == null) {
            return "";
        }
        Project project = projectMapper.selectById(run.getProjectId());
        return project == null || project.getOriginalRequirement() == null
                ? ""
                : project.getOriginalRequirement();
    }

    private JsonNode prdPayload(String content) {
        JsonNode parsed = object(content);
        return parsed.path("kind").asText().equals("PRD_READY") && parsed.path("prd").isObject()
                ? parsed.path("prd")
                : parsed;
    }

    private JsonNode answers(JsonNode context) {
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode responses = context.path("clarification_responses");
        if (responses.isArray()) {
            for (JsonNode response : responses) {
                if (response.path("answers").isArray()) {
                    result.addAll((ArrayNode) response.path("answers"));
                }
            }
        }
        return result;
    }

    private JsonNode assumptions(JsonNode context) {
        JsonNode request = context.path("clarification_request");
        return array(request.path("assumptions"));
    }

    private JsonNode resolutions(JsonNode context) {
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode responses = context.path("clarification_responses");
        if (responses.isArray()) {
            for (JsonNode response : responses) {
                if (response.path("conflict_resolutions").isArray()) {
                    result.addAll((ArrayNode) response.path("conflict_resolutions"));
                }
            }
        }
        return result;
    }

    private ArrayNode array(JsonNode value) {
        if (value != null && value.isArray()) {
            return (ArrayNode) value.deepCopy();
        }
        return objectMapper.createArrayNode();
    }

    private ArrayNode parsedArray(String value) {
        return array(object(value));
    }

    private JsonNode object(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored requirement baseline JSON is invalid", exception);
        }
    }
}
