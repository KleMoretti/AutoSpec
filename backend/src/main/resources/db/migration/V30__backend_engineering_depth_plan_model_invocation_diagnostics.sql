update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, model invocation diagnostics):
- BE-04: Project diagnostics now exposes failedModelInvocationCount, latestFailedModelInvocationAgentNode, latestFailedModelInvocationModelName, latestFailedModelInvocationDurationMs, and latestFailedModelInvocationErrorMessage so model-call status, latency, and failure cause are visible without manually querying model_invocation.
- BE-06: ProjectDiagnosticsResponse documents model invocation failure diagnostics in the packaged OpenAPI contract, and BackendApiContractTest guards those response fields.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%failedModelInvocationCount%';
