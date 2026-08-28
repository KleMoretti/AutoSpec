package com.autospec;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BackendApiContractTest {

    @Test
    void packagedOpenApiContractDocumentsOnlyTheCurrentProductApi() throws Exception {
        InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("contracts/autospec.openapi.yaml");

        assertThat(stream).isNotNull();
        String contract = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        assertThat(contract)
                .contains("openapi: 3.0.3")
                .contains("version: 5.0.0")
                .contains("/api/auth/login")
                .contains("/api/projects/{projectId}/artifacts")
                .contains("/api/projects/{projectId}/review")
                .contains("/api/projects/{projectId}/diagnostics")
                .contains("/api/projects/{projectId}/workflow-approvals")
                .contains("/api/projects/{projectId}/workflow-runs")
                .contains("/api/projects/{projectId}/code-skeleton")
                .contains("/api/projects/{projectId}/delivery-readiness")
                .contains("/api/projects/{projectId}/trace-graph")
                .contains("/api/projects/{projectId}/export")
                .contains("  /api/workflows:\n")
                .contains("/api/workflows/{versionId}/validate")
                .contains("/api/workflows/{versionId}/publish")
                .contains("/api/workflows/{workflowKey}/versions")
                .contains("  /api/workflow-runs:\n")
                .contains("/api/workflow-runs/{runId}/nodes")
                .contains("/api/workflow-runs/{runId}/metrics")
                .contains("/api/workflow-runs/{runId}/replay")
                .contains("/api/workflow-runs/{runId}/dead-letters")
                .contains("WorkflowDraftRequest")
                .contains("WorkflowValidationResponse")
                .contains("WorkflowDeadLetterResponse")
                .contains("WorkflowRunStartRequest")
                .contains("WorkflowExecutionPolicyRequest")
                .contains("WorkflowRuntimeMetricsResponse")
                .contains("WorkflowApprovalResponse")
                .contains("ProjectDiagnosticsResponse")
                .contains("latestEvaluationOverallScore")
                .contains("blockingReviewIssueCount")
                .contains("ApiErrorResponse")
                .contains("OptimisticLockConflict")
                .contains("PaginationCursor")
                .doesNotContain("/generate-v4")
                .doesNotContain("/generate-prd")
                .doesNotContain("ProjectProgressResponse")
                .doesNotContain("AgentStepStatus")
                .doesNotContain("WorkflowSnapshotResponse")
                .doesNotContain("ExternalCallLogResponse")
                .doesNotContain("AgentEventResponse")
                .doesNotContain("RetryTaskResponse")
                .doesNotContain("failedAgentTaskCount")
                .doesNotContain("latestFailedExternalCallErrorMessage");
    }
}
