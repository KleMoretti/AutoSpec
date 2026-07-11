update artifact
set content = concat(content, '

Execution evidence update (2026-07-08, review issue diagnostics):
- BE-04: GET /api/projects/{projectId}/diagnostics now exposes reviewIssueCount, openReviewIssueCount, blockingReviewIssueCount, latestOpenReviewIssueSeverity, latestOpenReviewIssueType, and latestOpenReviewIssueDescription so Reviewer and Evaluator findings are visible from the same operational view as workflow, Agent, model, external-call, and code-generation telemetry.
- BE-06: ProjectDiagnosticsResponse documents review issue diagnostics in the packaged OpenAPI contract, and BackendApiContractTest guards the response fields.
- BE-08: BackendDiagnosticsControllerTest verifies total, open, blocking, and latest open review issue aggregation in the project diagnostics regression.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%review issue diagnostics%';
