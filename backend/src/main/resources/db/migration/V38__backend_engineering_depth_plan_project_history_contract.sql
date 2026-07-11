update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, project history contract):
- BE-05: GET /api/projects and GET /api/projects/{projectId} enforce session-backed project visibility, returning only owner/editor/viewer accessible projects and rejecting cross-project detail reads.
- BE-06: ProjectResponse and the project list/detail endpoints are documented in contracts/autospec-backend-v1.openapi.yaml, and BackendApiContractTest guards the contract surface.
- BE-07: Project history listing now uses bounded limit/offset pagination through the shared PaginationRequest contract, with ProjectControllerTest covering tenant-scoped results and invalid pagination.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%project history contract%';
