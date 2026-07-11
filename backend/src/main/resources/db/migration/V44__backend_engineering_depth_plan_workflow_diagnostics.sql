update artifact
set content = concat(content, '

Execution evidence update (2026-07-06, workflow diagnostics):
- BE-04: GET /api/projects/{projectId}/diagnostics now exposes runningWorkflowRunCount and latestFailedWorkflowRunErrorMessage so stale workflow recovery and failed orchestration states are visible without querying workflow history manually.
- BE-06: ProjectDiagnosticsResponse documents workflowRunCount, runningWorkflowRunCount, failedWorkflowRunCount, and latestFailedWorkflowRunErrorMessage in the packaged OpenAPI contract.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%workflow diagnostics%';
