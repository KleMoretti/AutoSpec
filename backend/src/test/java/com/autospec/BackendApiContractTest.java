package com.autospec;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BackendApiContractTest {

    @Test
    void packagedOpenApiContractDocumentsCoreBackendEndpointsAndErrorModel() throws Exception {
        InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("contracts/autospec-backend-v1.openapi.yaml");

        assertThat(stream).isNotNull();
        String contract = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        assertThat(contract)
                .contains("openapi: 3.0.3")
                .contains("/api/contracts/openapi")
                .contains("/api/prompts/active")
                .contains("/api/projects")
                .contains("  /api/projects/{projectId}:\n")
                .contains("/api/projects/{projectId}/diagnostics")
                .contains("  /api/projects/{projectId}/generate:\n")
                .contains("/api/projects/{projectId}/generate-prd")
                .contains("/api/projects/{projectId}/generate-v4")
                .contains("/api/projects/{projectId}/continue")
                .contains("/api/projects/{projectId}/progress")
                .contains("/api/projects/{projectId}/workflow")
                .contains("/api/projects/{projectId}/code-skeleton")
                .contains("/api/projects/{projectId}/code-generation-jobs")
                .contains("/api/projects/{projectId}/code-generation-jobs/{jobId}/cancel")
                .contains("/api/projects/{projectId}/code-generation-jobs/{jobId}/retry")
                .contains("/api/projects/{projectId}/workflow-runs")
                .contains("/api/projects/{projectId}/workflow-runs/{runId}/cancel")
                .contains("/api/projects/{projectId}/audit-events")
                .contains("/api/projects/{projectId}/external-calls")
                .contains("/api/projects/{projectId}/model-invocations")
                .contains("  /api/projects/{projectId}/events:\n")
                .contains("/api/projects/{projectId}/events/history")
                .contains("/api/projects/{projectId}/knowledge/sources")
                .contains("/api/projects/{projectId}/review")
                .contains("/api/projects/{projectId}/tasks/{taskId}/retry")
                .contains("/api/projects/{projectId}/artifacts/{artifactId}")
                .contains("/api/projects/{projectId}/artifacts/{artifactId}/versions")
                .contains("/api/projects/{projectId}/artifacts/{artifactId}/approve")
                .contains("/api/projects/{projectId}/exports")
                .contains("/api/projects/{projectId}/exports/{exportFileId}")
                .contains("ApiErrorResponse")
                .contains("UpdateArtifactRequest")
                .contains("ApproveArtifactResponse")
                .contains("PromptVersionResponse")
                .contains("ProjectResponse")
                .contains("ProjectProgressResponse")
                .contains("AgentStepStatus")
                .contains("WorkflowSnapshotResponse")
                .contains("KnowledgeSourceResponse")
                .contains("CodeGenerationResponse")
                .contains("CodeGenerationJobResponse")
                .contains("WorkflowRunResponse")
                .contains("AuditEventResponse")
                .contains("ExternalCallLogResponse")
                .contains("ModelInvocationResponse")
                .contains("AgentEventResponse")
                .contains("ReviewResponse")
                .contains("RetryTaskResponse")
                .contains("workflowRunCount")
                .contains("runningWorkflowRunCount")
                .contains("failedWorkflowRunCount")
                .contains("cancelledWorkflowRunCount")
                .contains("latestFailedWorkflowRunErrorMessage")
                .contains("agentTaskCount")
                .contains("failedAgentTaskCount")
                .contains("latestFailedAgentTaskNodeName")
                .contains("latestFailedAgentTaskErrorMessage")
                .contains("latestFailedExternalCallErrorMessage")
                .contains("latestFailedExternalCallDurationMs")
                .contains("failedModelInvocationCount")
                .contains("latestFailedModelInvocationAgentNode")
                .contains("latestFailedModelInvocationModelName")
                .contains("latestFailedModelInvocationDurationMs")
                .contains("latestFailedModelInvocationErrorMessage")
                .contains("promptVersionId")
                .contains("codeGenerationJobCount")
                .contains("runningCodeGenerationJobCount")
                .contains("failedCodeGenerationJobCount")
                .contains("cancelledCodeGenerationJobCount")
                .contains("latestFailedCodeGenerationJobErrorMessage")
                .contains("latestEvaluationOverallScore")
                .contains("latestEvaluationGrade")
                .contains("latestEvaluationIssueCount")
                .contains("reviewIssueCount")
                .contains("openReviewIssueCount")
                .contains("blockingReviewIssueCount")
                .contains("latestOpenReviewIssueSeverity")
                .contains("latestOpenReviewIssueType")
                .contains("latestOpenReviewIssueDescription")
                .contains("PaginationLimit")
                .contains("PaginationOffset")
                .contains("X-AutoSpec-Session-Token")
                .contains("Idempotency-Key");
    }
}
