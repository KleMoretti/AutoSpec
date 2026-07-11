update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, generation lifecycle OpenAPI contract):
- BE-02: The packaged OpenAPI contract now documents POST /api/projects/{projectId}/generate, POST /api/projects/{projectId}/generate-prd, POST /api/projects/{projectId}/generate-v4, POST /api/projects/{projectId}/continue, and GET /api/projects/{projectId}/progress so generation entrypoints, continuation, and progress polling are contract-visible.
- BE-04: GET /api/projects/{projectId}/workflow, GET /api/projects/{projectId}/events, and GET /api/projects/{projectId}/knowledge/sources are documented so workflow snapshot inspection, live Agent event streaming, and indexed knowledge source review are visible from the backend contract.
- BE-06: ProjectProgressResponse, AgentStepStatus, WorkflowSnapshotResponse, and KnowledgeSourceResponse are documented in contracts/autospec-backend-v1.openapi.yaml, and BackendApiContractTest guards those paths and schemas.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%ProjectProgressResponse%';
