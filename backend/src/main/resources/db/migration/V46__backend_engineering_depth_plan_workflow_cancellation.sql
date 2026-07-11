update artifact
set content = concat(content, '

Execution evidence update (2026-07-06, workflow run cancellation):
- BE-03: RUNNING workflow_run records can now be cancelled through WorkflowRunService.cancelRunningRun and POST /api/projects/{projectId}/workflow-runs/{runId}/cancel, moving the run to CANCELLED with completed_at and a user-request error message.
- BE-05: Workflow run cancellation is OWNER/EDITOR scoped; VIEWER members and cross-project non-members are denied by ProjectControllerTest regressions.
- BE-06: The packaged OpenAPI contract documents POST /api/projects/{projectId}/workflow-runs/{runId}/cancel with WorkflowRunResponse plus 401/403/404/409 outcomes.
- BE-08: WorkflowRunServiceTest verifies the state transition and terminal-run conflict behavior; ProjectControllerTest verifies the HTTP path and authorization boundary.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%workflow run cancellation%';
