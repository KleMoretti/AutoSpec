update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, prompt governance OpenAPI contract):
- BE-01: GET /api/prompts/active is documented so active Agent prompt keys, versions, checksums, active flags, and creation timestamps are part of the backend service boundary.
- BE-06: PromptVersionResponse is documented in contracts/autospec-backend-v1.openapi.yaml, and BackendApiContractTest guards the prompt governance path and schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%PromptVersionResponse%';
