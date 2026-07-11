update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, code generation OpenAPI contract):
- BE-03: The packaged OpenAPI contract now documents POST /api/projects/{projectId}/code-skeleton, GET /api/projects/{projectId}/code-generation-jobs, POST /api/projects/{projectId}/code-generation-jobs/{jobId}/cancel, and POST /api/projects/{projectId}/code-generation-jobs/{jobId}/retry so durable code generation operations are reviewable by frontend tooling and CI.
- BE-06: CodeGenerationResponse and CodeGenerationJobResponse are documented in contracts/autospec-backend-v1.openapi.yaml, and BackendApiContractTest guards the path and schema coverage.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%CodeGenerationJobResponse%';
