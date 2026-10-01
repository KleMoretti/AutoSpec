package com.autospec.dto;

import java.util.List;
import java.util.Map;

/**
 * Public, read-only evaluation evidence for the platform governance view.
 * Nullable measurements are intentional: an unexecuted group is not a zero.
 */
public record EvaluationComparisonResponse(
        String status,
        String source,
        EvaluationMatrixResponse matrix,
        EvaluationGateDecisionResponse decision,
        String notEvaluatedReason
) {
    public record EvaluationMatrixResponse(
            String matrixId,
            String datasetVersion,
            String datasetHash,
            String datasetSplit,
            Long generatedAtEpochMs,
            List<EvaluationRunResponse> runs
    ) {
    }

    public record EvaluationRunResponse(
            String runId,
            String datasetVersion,
            String datasetHash,
            String datasetSplit,
            String environmentHash,
            Map<String, Object> pricingSnapshot,
            String group,
            String groupName,
            String executionMode,
            String workflowKey,
            String workflowVersion,
            String codeVersion,
            String bundleHash,
            Map<String, String> promptSchemaVersions,
            String modelVersion,
            String retrieverVersion,
            String toolPolicyVersion,
            String budgetVersion,
            Integer randomSeed,
            String status,
            String gateStatus,
            String decision,
            String notExecutedReason,
            List<EvaluationCaseResponse> caseResults,
            List<EvaluationMetricResponse> metrics
    ) {
    }

    public record EvaluationCaseResponse(
            String caseId,
            Integer repetition,
            String workflowRunId,
            String traceId,
            String bundleHash,
            String rubricRef,
            String status,
            Boolean gatePass,
            Double mustTraceCoverage,
            Integer blockingIssueCount,
            Integer unauthorizedToolRequests,
            Integer invalidToolArguments,
            Integer toolCallCount,
            Integer unauthorizedToolExecutions,
            Integer schemaInvalidCount,
            Double durationMs,
            Integer steps,
            Integer replans,
            Integer pathOscillations,
            Double p95LatencyMs,
            Integer tokens,
            Double cost,
            List<String> failureCodes
    ) {
    }

    public record EvaluationMetricResponse(
            String name,
            String status,
            Double value,
            String unit,
            String source,
            String note,
            Double intervalLow,
            Double intervalHigh,
            Integer sampleCount,
            String statisticVersion
    ) {
    }

    public record EvaluationGateDecisionResponse(
            String decision,
            String gateStatus,
            List<String> reasons,
            String baselineRunId,
            String candidateRunId,
            String statisticsVersion,
            Map<String, Object> pairedStatistics
    ) {
    }
}
