update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, failure diagnostics):
- BE-04: Project diagnostics now exposes latestFailedAgentTaskNodeName, latestFailedAgentTaskErrorMessage, latestFailedExternalCallErrorMessage, and latestFailedExternalCallDurationMs so production diagnosis includes the latest failed node, error cause, and external-call latency without manually joining telemetry tables.
- BE-06: ProjectDiagnosticsResponse documents the latest failure and latency fields in the packaged OpenAPI contract, and BackendApiContractTest guards those response fields.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%latestFailedExternalCallDurationMs%';
