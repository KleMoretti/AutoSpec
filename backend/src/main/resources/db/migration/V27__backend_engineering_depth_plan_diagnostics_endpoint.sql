update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, diagnostics endpoint):
- BE-04: GET /api/projects/{projectId}/diagnostics summarizes workflow run count, failed workflow run count, latest workflow run id, latest correlation id, audit event count, external call count, failed external call count, and model invocation count for authorized project members; BackendDiagnosticsControllerTest verifies the workflow, audit, external call, and model telemetry aggregation.
- BE-06: ProjectDiagnosticsResponse is documented in the packaged OpenAPI contract so diagnostics output remains reviewable by frontend tooling and contract checks.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%BackendDiagnosticsControllerTest%';
