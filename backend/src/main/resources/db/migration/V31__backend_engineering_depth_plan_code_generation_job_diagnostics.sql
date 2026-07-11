update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, code generation job diagnostics):
- BE-03: Project diagnostics now exposes codeGenerationJobCount, runningCodeGenerationJobCount, failedCodeGenerationJobCount, cancelledCodeGenerationJobCount, and latestFailedCodeGenerationJobErrorMessage so durable async job state is visible from the same project-level operational view as workflow and Agent telemetry.
- BE-04: BackendDiagnosticsControllerTest verifies code generation job total/RUNNING/FAILED/CANCELLED counts and latest failed job error in GET /api/projects/{projectId}/diagnostics.
- BE-06: ProjectDiagnosticsResponse documents code generation job diagnostics in the packaged OpenAPI contract, and BackendApiContractTest guards those response fields.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%codeGenerationJobCount%';
