update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, artifact lifecycle OpenAPI contract):
- BE-02: The packaged OpenAPI contract now documents PUT /api/projects/{projectId}/artifacts/{artifactId}, GET /api/projects/{projectId}/artifacts/{artifactId}/versions, and POST /api/projects/{projectId}/artifacts/{artifactId}/approve so artifact draft updates, version history, and approval workflow are reviewable.
- BE-06: UpdateArtifactRequest and ApproveArtifactResponse are documented in contracts/autospec-backend-v1.openapi.yaml, and BackendApiContractTest guards those paths and schemas.
- BE-07: Artifact lifecycle contract coverage includes version lineage metadata, sourceAgent, parentArtifactId, approvedAt, createdAt, updatedAt, and bounded version history pagination.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%ApproveArtifactResponse%';
