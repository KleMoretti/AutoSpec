update artifact
set content = concat(content, '

Execution evidence update (2026-07-04, OpenAPI endpoint):
- BE-06: GET /api/contracts/openapi serves the packaged OpenAPI YAML without requiring a session token so frontend tooling, CI checks, and reviewers can retrieve the backend contract directly; BackendApiContractEndpointTest verifies the endpoint status, application/yaml media type, and key contract content.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%BackendApiContractEndpointTest%';
