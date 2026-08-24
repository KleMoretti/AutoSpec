package com.autospec.service;

import com.autospec.dto.AgentStepStatus;
import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.dto.ProjectProgressResponse;
import com.autospec.entity.AgentTask;
import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.entity.ReviewIssue;
import com.autospec.entity.WorkflowRun;
import com.autospec.util.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentOrchestrationService {

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
    private final WorkflowRunService workflowRunService;
    private final AuditEventService auditEventService;
    private final ExternalCallLogService externalCallLogService;
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
            WorkflowRunService workflowRunService,
            AuditEventService auditEventService,
            ExternalCallLogService externalCallLogService,
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
        this.workflowRunService = workflowRunService;
        this.auditEventService = auditEventService;
        this.externalCallLogService = externalCallLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProjectProgressResponse generate(Long projectId) {
        return generate(projectId, getProjectOrThrow(projectId).getUserId());
    }

    @Transactional
    public ProjectProgressResponse generate(Long projectId, Long actorUserId) {
        Project project = getProjectOrThrow(projectId);
        workflowSnapshotService.ensureDefaultSnapshot(projectId);
        project.setStatus("GENERATING");
        projectService.updateById(project);

        AgentGenerationResult generationResult = agentEngineClient.generate(project.getOriginalRequirement());
        recordTasks(projectId, generationResult);
        saveArtifacts(project, generationResult, AgentWorkflowStage.LEGACY_GENERATION_OUTPUTS, "GENERATED");

        project.setStatus("COMPLETED");
        projectService.updateById(project);
        return progress(projectId);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ProjectProgressResponse generateV4(Long projectId, String idempotencyKey) {
        return generateV4(projectId, idempotencyKey, getProjectOrThrow(projectId).getUserId());
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ProjectProgressResponse generateV4(Long projectId, String idempotencyKey, Long actorUserId) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        if (normalizedKey == null) {
            return generateV4Internal(projectId, null, null, null, actorUserId);
        }

        WorkflowRun existing = workflowRunService.lambdaQuery()
                .eq(WorkflowRun::getProjectId, projectId)
                .eq(WorkflowRun::getOperation, "GENERATE_V4")
                .eq(WorkflowRun::getIdempotencyKey, normalizedKey)
                .oneOpt()
                .orElse(null);
        if (existing != null) {
            if ("COMPLETED".equals(existing.getStatus())) {
                return progress(projectId);
            }
            if ("FAILED".equals(existing.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Workflow run failed: " + existing.getErrorMessage());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow run is already in progress");
        }

        WorkflowRun run = new WorkflowRun();
        run.setProjectId(projectId);
        run.setOperation("GENERATE_V4");
        run.setIdempotencyKey(normalizedKey);
        run.setCorrelationId(newCorrelationId());
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        workflowRunService.save(run);
        auditWorkflowRun(projectId, run, actorUserId, "WORKFLOW_RUN_STARTED", "V4 workflow run started");

        try {
            ProjectProgressResponse response = generateV4Internal(
                    projectId,
                    normalizedKey,
                    run.getId(),
                    run.getCorrelationId(),
                    actorUserId
            );
            run.setStatus("COMPLETED");
            run.setResponseStatus(response.status());
            run.setResponsePercent(response.percent());
            run.setCompletedAt(LocalDateTime.now());
            workflowRunService.updateById(run);
            auditWorkflowRun(projectId, run, actorUserId, "WORKFLOW_RUN_COMPLETED", "V4 workflow run completed");
            return response;
        } catch (ResponseStatusException ex) {
            markWorkflowRunFailed(projectId, run, actorUserId, ex.getReason() == null ? ex.getMessage() : ex.getReason());
            throw ex;
        } catch (Exception ex) {
            markWorkflowRunFailed(projectId, run, actorUserId, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Workflow generation failed", ex);
        }
    }

    @Transactional
    public ProjectProgressResponse generateV4(Long projectId) {
        Project project = getProjectOrThrow(projectId);
        return generateV4Internal(projectId, null, null, null, project.getUserId());
    }

    private ProjectProgressResponse generateV4Internal(
            Long projectId,
            String idempotencyKey,
            Long workflowRunId,
            String correlationId,
            Long actorUserId
    ) {
        Project project = getProjectOrThrow(projectId);
        workflowSnapshotService.ensureDefaultSnapshot(projectId);
        project.setStatus("GENERATING");
        projectService.updateById(project);

        List<KnowledgeSourceResponse> retrievedSources = knowledgeIndexService.retrieveForProject(
                project.getOriginalRequirement(),
                5,
                projectId
        );
        AgentGenerationResult generationResult = callGenerateV4AgentEngine(project, retrievedSources, idempotencyKey, workflowRunId, correlationId);
        recordTasks(projectId, generationResult, workflowRunId, correlationId);
        saveArtifacts(project, generationResult, AgentWorkflowStage.V4, "GENERATED");

        project.setStatus("COMPLETED");
        projectService.updateById(project);
        return progress(projectId);
    }

    private AgentGenerationResult callGenerateV4AgentEngine(
            Project project,
            List<KnowledgeSourceResponse> retrievedSources,
            String idempotencyKey,
            Long workflowRunId,
            String correlationId
    ) {
        LocalDateTime startedAt = LocalDateTime.now();
        long startedNanos = System.nanoTime();
        String requestContext = agentEngineRequestContext(project, retrievedSources, idempotencyKey, workflowRunId, correlationId);
        try {
            AgentGenerationResult result = agentEngineClient.generateV4(project.getOriginalRequirement(), retrievedSources);
            recordExternalCall(project.getId(), correlationId, "GENERATE_V4", "SUCCEEDED", startedAt, startedNanos, requestContext, null);
            return result;
        } catch (ResponseStatusException ex) {
            recordExternalCall(project.getId(), correlationId, "GENERATE_V4", "FAILED", startedAt, startedNanos, requestContext, responseStatusMessage(ex));
            throw ex;
        } catch (RuntimeException ex) {
            recordExternalCall(project.getId(), correlationId, "GENERATE_V4", "FAILED", startedAt, startedNanos, requestContext, ex.getMessage());
            throw ex;
        }
    }

    private void recordExternalCall(
            Long projectId,
            String correlationId,
            String operation,
            String status,
            LocalDateTime startedAt,
            long startedNanos,
            String requestContext,
            String errorMessage
    ) {
        externalCallLogService.record(
                projectId,
                "agent-engine",
                correlationId,
                operation,
                status,
                elapsedMillis(startedNanos),
                requestContext,
                errorMessage,
                startedAt,
                LocalDateTime.now()
        );
    }

    private int elapsedMillis(long startedNanos) {
        long elapsed = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private String agentEngineRequestContext(
            Project project,
            List<KnowledgeSourceResponse> retrievedSources,
            String idempotencyKey,
            Long workflowRunId,
            String correlationId
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("workflowRunId", workflowRunId);
        context.put("correlationId", correlationId);
        context.put("idempotencyKey", idempotencyKey);
        context.put("requirementLength", project.getOriginalRequirement() == null ? 0 : project.getOriginalRequirement().length());
        context.put("retrievedSourceCount", retrievedSources == null ? 0 : retrievedSources.size());
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String responseStatusMessage(ResponseStatusException ex) {
        return ex.getReason() == null ? ex.getMessage() : ex.getReason();
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }

    private void markWorkflowRunFailed(Long projectId, WorkflowRun run, Long actorUserId, String message) {
        run.setStatus("FAILED");
        run.setErrorMessage(message == null || message.isBlank() ? "Workflow generation failed" : message);
        run.setCompletedAt(LocalDateTime.now());
        workflowRunService.updateById(run);
        Project project = projectService.getById(projectId);
        if (project != null) {
            project.setStatus("FAILED");
            projectService.updateById(project);
        }
        auditWorkflowRun(projectId, run, actorUserId, "WORKFLOW_RUN_FAILED", "V4 workflow run failed");
    }

    private void auditWorkflowRun(
            Long projectId,
            WorkflowRun run,
            Long actorUserId,
            String eventType,
            String message
    ) {
        auditEventService.record(
                projectId,
                actorUserId,
                run.getCorrelationId(),
                eventType,
                "WORKFLOW_RUN",
                run.getId(),
                message,
                workflowRunMetadata(run)
        );
    }

    private String workflowRunMetadata(WorkflowRun run) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "operation", run.getOperation(),
                    "correlationId", run.getCorrelationId(),
                    "idempotencyKey", run.getIdempotencyKey(),
                    "status", run.getStatus()
            ));
        } catch (Exception ex) {
            return "{}";
        }
    }

    @Transactional
    public ProjectProgressResponse generatePrd(Long projectId) {
        return generatePrd(projectId, getProjectOrThrow(projectId).getUserId());
    }

    @Transactional
    public ProjectProgressResponse generatePrd(Long projectId, Long actorUserId) {
        Project project = getProjectOrThrow(projectId);
        workflowSnapshotService.ensureDefaultSnapshot(projectId);
        project.setStatus("GENERATING");
        projectService.updateById(project);

        AgentGenerationResult result = agentEngineClient.generatePrd(
                project.getOriginalRequirement(),
                knowledgeIndexService.retrieveForProject(project.getOriginalRequirement(), 5, projectId)
        );
        recordTasks(projectId, result);
        saveStageArtifact(project, AgentWorkflowStage.PRODUCT_MANAGER, result.prdJson(), "PENDING_REVIEW");

        project.setStatus("PRD_REVIEW");
        projectService.updateById(project);
        return progress(projectId);
    }

    @Transactional
    public ProjectProgressResponse continueAfterApprovedPrd(Long projectId) {
        return continueAfterApprovedPrd(projectId, getProjectOrThrow(projectId).getUserId());
    }

    @Transactional
    public ProjectProgressResponse continueAfterApprovedPrd(Long projectId, Long actorUserId) {
        Project project = getProjectOrThrow(projectId);
        Artifact approvedPrd = artifactVersionService.latestApproved(projectId, "PRD");
        workflowSnapshotService.ensureDefaultSnapshot(projectId);

        project.setStatus("GENERATING");
        projectService.updateById(project);

        AgentGenerationResult result = agentEngineClient.continueAfterPrd(
                project.getOriginalRequirement(),
                approvedPrd.getContent(),
                knowledgeIndexService.retrieveForProject(project.getOriginalRequirement(), 5, projectId)
        );
        recordTasks(projectId, result);
        saveArtifacts(project, result, AgentWorkflowStage.AFTER_PRD, "GENERATED");

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

    private void recordTasks(Long projectId, AgentGenerationResult generationResult) {
        recordTasks(projectId, generationResult, null, null);
    }

    private void recordTasks(Long projectId, AgentGenerationResult generationResult, Long workflowRunId, String correlationId) {
        if (generationResult.records() != null && !generationResult.records().isEmpty()) {
            generationResult.records().forEach(record -> recordTask(projectId, record, null, workflowRunId, correlationId));
            return;
        }
        for (AgentWorkflowStage stage : AgentWorkflowStage.V4) {
            String output = stage.outputFrom(generationResult);
            if (output != null) {
                recordTask(
                        projectId,
                        new AgentEngineExecutionRecord(
                                stage.nodeName(),
                                stage.agentName(),
                                "SUCCEEDED",
                                null,
                                output,
                                null,
                                null,
                                stage.promptKey()
                        ),
                        null,
                        workflowRunId,
                        correlationId
                );
            }
        }
    }

    private AgentTask recordTask(Long projectId, AgentEngineExecutionRecord record, Long retryOfTaskId) {
        return recordTask(projectId, record, retryOfTaskId, null, null);
    }

    private AgentTask recordTask(Long projectId, AgentEngineExecutionRecord record, Long retryOfTaskId, Long workflowRunId, String correlationId) {
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
        recordModelInvocation(projectId, task, record, workflowRunId, correlationId);
        return task;
    }

    private void recordModelInvocation(Long projectId, AgentTask task, AgentEngineExecutionRecord record, Long workflowRunId, String correlationId) {
        com.autospec.entity.ModelInvocation invocation = new com.autospec.entity.ModelInvocation();
        invocation.setProjectId(projectId);
        invocation.setTaskId(task.getId());
        invocation.setWorkflowRunId(workflowRunId);
        invocation.setCorrelationId(correlationId);
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
        artifactService.lambdaUpdate()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, type)
                .in(Artifact::getStatus, "GENERATED", "PENDING_REVIEW")
                .set(Artifact::getStatus, "SUPERSEDED")
                .update();
        Artifact artifact = new Artifact();
        artifact.setProjectId(projectId);
        artifact.setType(type);
        artifact.setTitle(title);
        artifact.setContent(content);
        artifact.setFormat("JSON");
        artifact.setVersion(nextArtifactVersion(projectId, type));
        artifact.setStatus(status);
        artifact.setSourceAgent(sourceAgent);
        artifact.setContentHash(ContentHash.sha256(content));
        artifact.setSchemaVersion("v1");
        artifact.setPromptKey(sourceAgent);
        artifactService.save(artifact);
    }

    private void saveArtifacts(
            Project project,
            AgentGenerationResult result,
            List<AgentWorkflowStage> stages,
            String status
    ) {
        for (AgentWorkflowStage stage : stages) {
            saveStageArtifact(project, stage, stage.outputFrom(result), status);
        }
    }

    private void saveStageArtifact(Project project, AgentWorkflowStage stage, String content, String status) {
        saveArtifact(
                project.getId(),
                stage.artifactType(),
                stage.artifactTitle(project.getName()),
                content,
                stage.agentName(),
                status
        );
        if (stage == AgentWorkflowStage.REVIEWER) {
            saveReviewIssues(project.getId(), content);
        }
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
        List<AgentWorkflowStage> expected = expectedStages(tasks);
        List<String> finished = tasks.stream()
                .filter(task -> "SUCCEEDED".equals(task.getStatus()))
                .map(AgentTask::getAgentName)
                .toList();
        return expected.stream()
                .map(AgentWorkflowStage::agentName)
                .filter(agent -> !finished.contains(agent))
                .findFirst()
                .orElse("COMPLETED");
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
        int denominator = expectedStages(tasks).size();
        long succeeded = tasks.stream().filter(task -> "SUCCEEDED".equals(task.getStatus())).count();
        return Math.min((int) Math.round((succeeded * 100.0) / denominator), 100);
    }

    private List<AgentWorkflowStage> expectedStages(List<AgentTask> tasks) {
        if (containsStage(tasks, AgentWorkflowStage.EVALUATOR)) {
            return AgentWorkflowStage.V4;
        }
        if (containsStage(tasks, AgentWorkflowStage.ARCHITECT)
                || containsStage(tasks, AgentWorkflowStage.FRONTEND_ENGINEER)) {
            return AgentWorkflowStage.V2;
        }
        return AgentWorkflowStage.V1;
    }

    private boolean containsStage(List<AgentTask> tasks, AgentWorkflowStage stage) {
        return tasks.stream().anyMatch(task -> stage.matches(task.getNodeName(), task.getAgentName()));
    }

    private void saveNodeArtifactIfArtifactNode(Long projectId, String nodeName, String outputJson) {
        Project project = getProjectOrThrow(projectId);
        AgentWorkflowStage.forNode(nodeName)
                .filter(stage -> stage != AgentWorkflowStage.PRODUCT_MANAGER)
                .ifPresent(stage -> saveStageArtifact(project, stage, outputJson, "GENERATED"));
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
        return AgentWorkflowStage.forAgent(agentName)
                .map(AgentWorkflowStage::nodeName)
                .orElse(agentName);
    }

    private String newCorrelationId() {
        return "wf-" + UUID.randomUUID();
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
            reviewIssueService.lambdaUpdate()
                    .eq(ReviewIssue::getProjectId, projectId)
                    .eq(ReviewIssue::getStatus, "OPEN")
                    .set(ReviewIssue::getStatus, "SUPERSEDED")
                    .update();
            for (JsonNode issueNode : issues) {
                ReviewIssue issue = new ReviewIssue();
                issue.setProjectId(projectId);
                issue.setSeverity(issueNode.path("severity").asText("LOW"));
                issue.setIssueType(issueNode.path("issue_type").asText("SEMANTIC_REVIEW"));
                issue.setDescription(issueNode.path("description").asText(""));
                issue.setSuggestion(issueNode.path("suggestion").asText(""));
                issue.setIssueKey("LEGACY:" + ContentHash.sha256(
                        issue.getIssueType() + "|"
                                + issueNode.path("requirement_id").asText() + "|"
                                + issueNode.path("artifact_path").asText() + "|"
                                + issue.getDescription()
                ).substring(0, 24));
                issue.setRequirementId(issueNode.path("requirement_id").asText(null));
                issue.setArtifactPath(issueNode.path("artifact_path").asText(null));
                issue.setEvidence(issueNode.path("evidence").toString());
                issue.setStatus("OPEN");
                issue.setCreatedAt(LocalDateTime.now());
                issue.setUpdatedAt(LocalDateTime.now());
                reviewIssueService.save(issue);
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid review report JSON", ex);
        }
    }
}
