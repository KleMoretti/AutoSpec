update artifact
set content = concat(content, '

Execution evidence update (2026-07-04, OpenAPI contract):
- BE-06: The backend now packages contracts/autospec-backend-v1.openapi.yaml as a machine-readable OpenAPI 3.0.3 contract for core project, V4 generation, artifact list, export creation, export history, and export detail APIs; BackendApiContractTest verifies the contract is included in backend resources and documents ApiErrorResponse, PaginationLimit, PaginationOffset, X-AutoSpec-Session-Token, and Idempotency-Key.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%BackendApiContractTest%';
