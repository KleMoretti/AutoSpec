update artifact
set content = concat(content, '

Execution evidence update (2026-07-08, cancelled workflow diagnostics):
- BE-04: GET /api/projects/{projectId}/diagnostics now exposes cancelledWorkflowRunCount so cancelled orchestration runs are visible from the same operational view as RUNNING and FAILED workflow runs.
- BE-06: ProjectDiagnosticsResponse documents cancelledWorkflowRunCount in the packaged OpenAPI contract, and BackendApiContractTest guards the response field.
- BE-08: BackendDiagnosticsControllerTest verifies completed, RUNNING, FAILED, and CANCELLED workflow run aggregation in one project diagnostics regression.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%cancelled workflow diagnostics%';
