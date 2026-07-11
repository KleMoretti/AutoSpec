update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, observability OpenAPI contract):
- BE-04: The packaged OpenAPI contract now documents GET /api/projects/{projectId}/workflow-runs, GET /api/projects/{projectId}/audit-events, GET /api/projects/{projectId}/external-calls, GET /api/projects/{projectId}/model-invocations, GET /api/projects/{projectId}/events/history, GET /api/projects/{projectId}/review, and POST /api/projects/{projectId}/tasks/{taskId}/retry so workflow traces, audit trail, external-call telemetry, model telemetry, Agent event history, review output, and task retry are contract-visible.
- BE-06: WorkflowRunResponse, AuditEventResponse, ExternalCallLogResponse, ModelInvocationResponse, AgentEventResponse, ReviewResponse, ReviewIssueResponse, and RetryTaskResponse are documented in contracts/autospec-backend-v1.openapi.yaml, and BackendApiContractTest guards those paths and schemas.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%WorkflowRunResponse%';
