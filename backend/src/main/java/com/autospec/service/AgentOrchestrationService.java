package com.autospec.service;

import com.autospec.dto.AgentStepStatus;
import com.autospec.dto.ProjectProgressResponse;
import com.autospec.entity.AgentTask;
import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.entity.ReviewIssue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AgentOrchestrationService {

    private static final List<String> V1_AGENTS = List.of(
            "ProductManagerAgent_v1",
            "BackendEngineerAgent_v1",
            "ReviewerAgent_v1"
    );

    private static final List<String> V2_AGENTS = List.of(
            "ProductManagerAgent_v1",
            "ArchitectAgent_v1",
            "BackendEngineerAgent_v1",
            "FrontendEngineerAgent_v1",
            "ReviewerAgent_v1"
    );

    private static final List<String> V4_AGENTS = List.of(
            "ProductManagerAgent_v1",
            "ArchitectAgent_v1",
            "BackendEngineerAgent_v1",
            "FrontendEngineerAgent_v1",
            "ReviewerAgent_v1",
            "EvaluatorAgent_v1"
    );

    private final ProjectService projectService;
    private final AgentTaskService agentTaskService;
    private final ArtifactService artifactService;
    private final ReviewIssueService reviewIssueService;
    private final AgentEngineClient agentEngineClient;
    private final ArtifactVersionService artifactVersionService;
    private final AgentEventService agentEventService;
    private final PromptRegistryService promptRegistryService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final ModelInvocationService modelInvocationService;
    private final WorkflowSnapshotService workflowSnapshotService;
    private final ObjectMapper objectMapper;

    public AgentOrchestrationService(
            ProjectService projectService,
            AgentTaskService agentTaskService,
            ArtifactService artifactService,
            ReviewIssueService reviewIssueService,
            AgentEngineClient agentEngineClient,
            ArtifactVersionService artifactVersionService,
            AgentEventService agentEventService,
            PromptRegistryService promptRegistryService,
            KnowledgeIndexService knowledgeIndexService,
            ModelInvocationService modelInvocationService,
            WorkflowSnapshotService workflowSnapshotService,
            ObjectMapper objectMapper
    ) {
        this.projectService = projectService;
        this.agentTaskService = agentTaskService;
        this.artifactService = artifactService;
        this.reviewIssueService = reviewIssueService;
        this.agentEngineClient = agentEngineClient;
        this.artifactVersionService = artifactVersionService;
        this.agentEventService = agentEventService;
        this.promptRegistryService = promptRegistryService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.modelInvocationService = modelInvocationService;
        this.workflowSnapshotService = workflowSnapshotService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProjectProgressResponse generate(Long projectId) {
        Project project = getProjectOrThrow(projectId);
        workflowSnapshotService.ensureDefaultSnapshot(projectId);
        project.setStatus("GENERATING");
        projectService.updateById(project);

        clearGeneratedData(projectId);

        AgentGenerationResult generationResult = agentEngineClient.generate(project.getOriginalRequirement());
        recordTasks(projectId, generationResult);
        saveArtifact(projectId, "PRD", project.getName() + " PRD", generationResult.prdJson(), "ProductManagerAgent_v1", "GENERATED");
        saveArtifact(projectId, "BACKEND_DESIGN", project.getName() + " Backend Design", generationResult.backendDesignJson(), "BackendEngineerAgent_v1", "GENERATED");
        saveArtifact(projectId, "REVIEW_REPORT", project.getName() + " Review Report", generationResult.reviewReportJson(), "ReviewerAgent_v1", "GENERATED");
        saveArtifact(projectId, "EVALUATION_REPORT", project.getName() + " Evaluation Report", generationResult.evaluationReportJson(), "EvaluatorAgent_v1", "GENERATED");
        saveReviewIssues(projectId, generationResult.reviewReportJson());

        project.setStatus("COMPLETED");
        projectService.updateById(project);
        return progress(projectId);
    }

    @Transactional
    public ProjectProgressResponse generateV4(Long projectId) {
        Project project = getProjectOrThrow(projectId);
        workflowSnapshotService.ensureDefaultSnapshot(projectId);
        project.setStatus("GENERATING");
        projectService.updateById(project);

        clearGeneratedData(projectId);

        AgentGenerationResult generationResult = agentEngineClient.generateV4(
                project.getOriginalRequirement(),
                knowledgeIndexService.retrieve(project.getOriginalRequirement(), 5, project.getUserId())
        );
        recordTasks(projectId, generationResult);
        saveArtifact(projectId, "PRD", project.getName() + " PRD", generationResult.prdJson(), "ProductManagerAgent_v1", "GENERATED");
        saveArtifact(projectId, "ARCHITECTURE_DESIGN", project.getName() + " Architecture Design", generationResult.architectureDesignJson(), "ArchitectAgent_v1", "GENERATED");
        saveArtifact(projectId, "BACKEND_DESIGN", project.getName() + " Backend Design", generationResult.backendDesignJson(), "BackendEngineerAgent_v1", "GENERATED");
        saveArtifact(projectId, "FRONTEND_SKELETON", project.getName() + " Frontend Skeleton", generationResult.frontendSkeletonJson(), "FrontendEngineerAgent_v1", "GENERATED");
        saveArtifact(projectId, "REVIEW_REPORT", project.getName() + " Review Report", generationResult.reviewReportJson(), "ReviewerAgent_v1", "GENERATED");
        saveArtifact(projectId, "EVALUATION_REPORT", project.getName() + " Evaluation Report", generationResult.evaluationReportJson(), "EvaluatorAgent_v1", "GENERATED");
        saveReviewIssues(projectId, generationResult.reviewReportJson());

        project.setStatus("COMPLETED");
        projectService.updateById(project);
        return progress(projectId);
    }

    @Transactional
    public ProjectProgressResponse generatePrd(Long projectId) {
        Project project = getProjectOrThrow(projectId);
        workflowSnapshotService.ensureDefaultSnapshot(projectId);
        project.setStatus("GENERATING");
        projectService.updateById(project);

        clearGeneratedData(projectId);

        AgentGenerationResult result = agentEngineClient.generatePrd(
                project.getOriginalRequirement(),
                knowledgeIndexService.retrieve(project.getOriginalRequirement(), 5, project.getUserId())
        );
        recordTasks(projectId, result);
        saveArtifact(projectId, "PRD", project.getName() + " PRD", result.prdJson(), "ProductManagerAgent_v1", "PENDING_REVIEW");

        project.setStatus("PRD_REVIEW");
        projectService.updateById(project);
        return progress(projectId);
    }

    @Transactional
    public ProjectProgressResponse continueAfterApprovedPrd(Long projectId) {
        Project project = getProjectOrThrow(projectId);
        Artifact approvedPrd = artifactVersionService.latestApproved(projectId, "PRD");
        workflowSnapshotService.ensureDefaultSnapshot(projectId);

        project.setStatus("GENERATING");
        projectService.updateById(project);

        artifactService.lambdaUpdate()
                .eq(Artifact::getProjectId, projectId)
                .ne(Artifact::getType, "PRD")
                .remove();
        reviewIssueService.lambdaUpdate().eq(ReviewIssue::getProjectId, projectId).remove();

        AgentGenerationResult result = agentEngineClient.continueAfterPrd(
                project.getOriginalRequirement(),
                approvedPrd.getContent(),
                knowledgeIndexService.retrieve(project.getOriginalRequirement(), 5, project.getUserId())
        );
        recordTasks(projectId, result);
        saveArtifact(projectId, "ARCHITECTURE_DESIGN", project.getName() + " Architecture Design", result.architectureDesignJson(), "ArchitectAgent_v1", "GENERATED");
        saveArtifact(projectId, "BACKEND_DESIGN", project.getName() + " Backend Design", result.backendDesignJson(), "BackendEngineerAgent_v1", "GENERATED");
        saveArtifact(projectId, "FRONTEND_SKELETON", project.getName() + " Frontend Skeleton", result.frontendSkeletonJson(), "FrontendEngineerAgent_v1", "GENERATED");
        saveArtifact(projectId, "REVIEW_REPORT", project.getName() + " Review Report", result.reviewReportJson(), "ReviewerAgent_v1", "GENERATED");
        saveArtifact(projectId, "EVALUATION_REPORT", project.getName() + " Evaluation Report", result.evaluationReportJson(), "EvaluatorAgent_v1", "GENERATED");
        saveReviewIssues(projectId, result.reviewReportJson());

        project.setStatus("COMPLETED");
        projectService.updateById(project);
        return progress(projectId);
    }

    @Transactional
    public AgentTask retryTask(Long projectId, Long taskId) {
        getProjectOrThrow(projectId);
        AgentTask failed = agentTaskService.getById(taskId);
        if (failed == null || !projectId.equals(failed.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }
        if (!"FAILED".equals(failed.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only failed tasks can be retried");
        }
        AgentEngineExecutionRecord record = agentEngineClient.runNode(failed.getNodeName(), failed.getInputText());
        AgentTask retry = recordTask(projectId, record, failed.getId());
        if ("SUCCEEDED".equals(retry.getStatus())) {
            saveNodeArtifactIfArtifactNode(projectId, retry.getNodeName(), retry.getOutputText());
        }
        return retry;
    }

    public ProjectProgressResponse progress(Long projectId) {
        Project project = getProjectOrThrow(projectId);
        List<AgentTask> tasks = agentTaskService.lambdaQuery()
                .eq(AgentTask::getProjectId, projectId)
                .orderByAsc(AgentTask::getId)
                .list();
        List<AgentStepStatus> steps = tasks.stream()
                .map(task -> new AgentStepStatus(
                        task.getId(),
                        task.getAgentName(),
                        task.getNodeName(),
                        task.getStatus(),
                        task.getDurationMs(),
                        task.getRetryOfTaskId(),
                        task.getErrorMessage()
                ))
                .toList();

        int percent = percent(project, tasks);
        String currentAgent = percent >= 100 ? "COMPLETED" : nextAgentName(tasks);
        return new ProjectProgressResponse(projectId, project.getStatus(), currentAgent, percent, steps);
    }

    public Integer reviewScore(Long projectId) {
        Artifact reviewArtifact = artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, "REVIEW_REPORT")
                .orderByDesc(Artifact::getVersion)
                .last("limit 1")
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review report not found"));
        try {
            JsonNode root = objectMapper.readTree(reviewArtifact.getContent());
            return root.path("score").asInt(0);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid review report JSON", ex);
        }
    }

    private Project getProjectOrThrow(Long projectId) {
        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return project;
    }

    private void clearGeneratedData(Long projectId) {
        artifactService.lambdaUpdate().eq(Artifact::getProjectId, projectId).remove();
        reviewIssueService.lambdaUpdate().eq(ReviewIssue::getProjectId, projectId).remove();
        agentTaskService.lambdaUpdate().eq(AgentTask::getProjectId, projectId).remove();
    }

    private void recordTasks(Long projectId, AgentGenerationResult generationResult) {
        if (generationResult.records() != null && !generationResult.records().isEmpty()) {
            generationResult.records().forEach(record -> recordTask(projectId, record, null));
            return;
        }
        if (generationResult.prdJson() != null) {
            recordTask(projectId, new AgentEngineExecutionRecord("product_manager", "ProductManagerAgent_v1", "SUCCEEDED", null, generationResult.prdJson(), null, null, "ProductManagerAgent"), null);
        }
        if (generationResult.architectureDesignJson() != null) {
            recordTask(projectId, new AgentEngineExecutionRecord("architect", "ArchitectAgent_v1", "SUCCEEDED", null, generationResult.architectureDesignJson(), null, null, "ArchitectAgent"), null);
        }
        if (generationResult.backendDesignJson() != null) {
            recordTask(projectId, new AgentEngineExecutionRecord("backend_engineer", "BackendEngineerAgent_v1", "SUCCEEDED", null, generationResult.backendDesignJson(), null, null, "BackendEngineerAgent"), null);
        }
        if (generationResult.frontendSkeletonJson() != null) {
            recordTask(projectId, new AgentEngineExecutionRecord("frontend_engineer", "FrontendEngineerAgent_v1", "SUCCEEDED", null, generationResult.frontendSkeletonJson(), null, null, "FrontendEngineerAgent"), null);
        }
        if (generationResult.reviewReportJson() != null) {
            recordTask(projectId, new AgentEngineExecutionRecord("reviewer", "ReviewerAgent_v1", "SUCCEEDED", null, generationResult.reviewReportJson(), null, null, "ReviewerAgent"), null);
        }
        if (generationResult.evaluationReportJson() != null) {
            recordTask(projectId, new AgentEngineExecutionRecord("evaluator", "EvaluatorAgent_v1", "SUCCEEDED", null, generationResult.evaluationReportJson(), null, null, "EvaluatorAgent"), null);
        }
    }

    private AgentTask recordTask(Long projectId, AgentEngineExecutionRecord record, Long retryOfTaskId) {
        AgentTask task = new AgentTask();
        task.setProjectId(projectId);
        task.setAgentName(record.agentName());
        task.setNodeName(record.nodeName() == null ? inferNodeName(record.agentName()) : record.nodeName());
        task.setStatus(record.status() == null ? "SUCCEEDED" : record.status());
        task.setInputText(record.inputJson());
        task.setOutputText(record.outputJson());
        task.setErrorMessage(record.errorMessage());
        task.setDurationMs(record.durationMs());
        task.setRetryOfTaskId(retryOfTaskId);
        task.setPromptVersionId(promptRegistryService.activePromptIdOrNull(promptKey(record)));
        task.setStartTime(LocalDateTime.now().minusSeconds(1));
        task.setEndTime(LocalDateTime.now());
        agentTaskService.save(task);
        String eventType = "FAILED".equals(task.getStatus()) ? "NODE_FAILED" : "NODE_SUCCEEDED";
        agentEventService.record(
                projectId,
                task.getId(),
                eventType,
                task.getNodeName(),
                task.getAgentName() + " " + task.getStatus().toLowerCase(),
                task.getOutputText()
        );
        recordModelInvocation(projectId, task, record);
        return task;
    }

    private void recordModelInvocation(Long projectId, AgentTask task, AgentEngineExecutionRecord record) {
        com.autospec.entity.ModelInvocation invocation = new com.autospec.entity.ModelInvocation();
        invocation.setProjectId(projectId);
        invocation.setTaskId(task.getId());
        invocation.setProviderKey(record.providerKey() == null ? "local" : record.providerKey());
        invocation.setModelName(record.modelName() == null ? "deterministic-fixture" : record.modelName());
        invocation.setAgentNode(task.getNodeName());
        invocation.setPromptVersionId(task.getPromptVersionId());
        invocation.setStatus(task.getStatus());
        invocation.setDurationMs(task.getDurationMs() == null ? 0 : task.getDurationMs());
        invocation.setInputTokens(0);
        invocation.setOutputTokens(0);
        invocation.setScore("SUCCEEDED".equals(task.getStatus()) ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
        invocation.setErrorMessage(task.getErrorMessage());
        modelInvocationService.save(invocation);
    }

    private void saveArtifact(Long projectId, String type, String title, String content, String sourceAgent, String status) {
        if (content == null || content.isBlank()) {
            return;
        }
        Artifact artifact = new Artifact();
        artifact.setProjectId(projectId);
        artifact.setType(type);
        artifact.setTitle(title);
        artifact.setContent(content);
        artifact.setFormat("JSON");
        artifact.setVersion(nextArtifactVersion(projectId, type));
        artifact.setStatus(status);
        artifact.setSourceAgent(sourceAgent);
        artifactService.save(artifact);
    }

    private int nextArtifactVersion(Long projectId, String type) {
        return artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, type)
                .list()
                .stream()
                .map(Artifact::getVersion)
                .filter(version -> version != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private String nextAgentName(List<AgentTask> tasks) {
        List<String> expected = isV4(tasks) ? V4_AGENTS : isV2(tasks) ? V2_AGENTS : V1_AGENTS;
        List<String> finished = tasks.stream()
                .filter(task -> "SUCCEEDED".equals(task.getStatus()))
                .map(AgentTask::getAgentName)
                .toList();
        return expected.stream().filter(agent -> !finished.contains(agent)).findFirst().orElse("COMPLETED");
    }

    private int percent(Project project, List<AgentTask> tasks) {
        if ("COMPLETED".equals(project.getStatus())) {
            return 100;
        }
        if ("PRD_REVIEW".equals(project.getStatus()) || "PRD_APPROVED".equals(project.getStatus())) {
            return 20;
        }
        if (tasks.isEmpty()) {
            return 0;
        }
        int denominator = isV4(tasks) ? V4_AGENTS.size() : isV2(tasks) ? V2_AGENTS.size() : V1_AGENTS.size();
        long succeeded = tasks.stream().filter(task -> "SUCCEEDED".equals(task.getStatus())).count();
        return Math.min((int) Math.round((succeeded * 100.0) / denominator), 100);
    }

    private boolean isV4(List<AgentTask> tasks) {
        return tasks.stream().anyMatch(task ->
                "EvaluatorAgent_v1".equals(task.getAgentName())
                        || "evaluator".equals(task.getNodeName()));
    }

    private boolean isV2(List<AgentTask> tasks) {
        return tasks.stream().anyMatch(task ->
                "ArchitectAgent_v1".equals(task.getAgentName())
                        || "FrontendEngineerAgent_v1".equals(task.getAgentName())
                        || "architect".equals(task.getNodeName())
                        || "frontend_engineer".equals(task.getNodeName()));
    }

    private void saveNodeArtifactIfArtifactNode(Long projectId, String nodeName, String outputJson) {
        Project project = getProjectOrThrow(projectId);
        if ("architect".equals(nodeName)) {
            saveArtifact(projectId, "ARCHITECTURE_DESIGN", project.getName() + " Architecture Design", outputJson, "ArchitectAgent_v1", "GENERATED");
        } else if ("backend_engineer".equals(nodeName)) {
            saveArtifact(projectId, "BACKEND_DESIGN", project.getName() + " Backend Design", outputJson, "BackendEngineerAgent_v1", "GENERATED");
        } else if ("frontend_engineer".equals(nodeName)) {
            saveArtifact(projectId, "FRONTEND_SKELETON", project.getName() + " Frontend Skeleton", outputJson, "FrontendEngineerAgent_v1", "GENERATED");
        } else if ("reviewer".equals(nodeName)) {
            saveArtifact(projectId, "REVIEW_REPORT", project.getName() + " Review Report", outputJson, "ReviewerAgent_v1", "GENERATED");
            saveReviewIssues(projectId, outputJson);
        } else if ("evaluator".equals(nodeName)) {
            saveArtifact(projectId, "EVALUATION_REPORT", project.getName() + " Evaluation Report", outputJson, "EvaluatorAgent_v1", "GENERATED");
        }
    }

    private String promptKey(AgentEngineExecutionRecord record) {
        if (record.promptKey() != null && !record.promptKey().isBlank()) {
            return record.promptKey();
        }
        if (record.agentName() == null) {
            return "";
        }
        return record.agentName().replaceFirst("_v\\d+$", "");
    }

    private String inferNodeName(String agentName) {
        if (agentName == null) {
            return "unknown";
        }
        return switch (agentName) {
            case "ProductManagerAgent_v1" -> "product_manager";
            case "ArchitectAgent_v1" -> "architect";
            case "BackendEngineerAgent_v1" -> "backend_engineer";
            case "FrontendEngineerAgent_v1" -> "frontend_engineer";
            case "ReviewerAgent_v1" -> "reviewer";
            case "EvaluatorAgent_v1" -> "evaluator";
            default -> agentName;
        };
    }

    private void saveReviewIssues(Long projectId, String reviewJson) {
        if (reviewJson == null || reviewJson.isBlank()) {
            return;
        }
        try {
            JsonNode issues = objectMapper.readTree(reviewJson).path("issues");
            if (!issues.isArray()) {
                return;
            }
            for (JsonNode issueNode : issues) {
                ReviewIssue issue = new ReviewIssue();
                issue.setProjectId(projectId);
                issue.setSeverity(issueNode.path("severity").asText("LOW"));
                issue.setIssueType(issueNode.path("issue_type").asText("SEMANTIC_REVIEW"));
                issue.setDescription(issueNode.path("description").asText(""));
                issue.setSuggestion(issueNode.path("suggestion").asText(""));
                issue.setStatus("OPEN");
                reviewIssueService.save(issue);
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid review report JSON", ex);
        }
    }
}
