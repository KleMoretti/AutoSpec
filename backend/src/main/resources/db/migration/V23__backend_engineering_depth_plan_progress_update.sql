update artifact
set content = concat(content, '

Execution evidence update (2026-07-04):
- BE-02: Markdown/PDF export persistence and PROJECT_EXPORTED audit writes share one transaction; ExportTransactionTest verifies export_file rows are rolled back when audit recording fails.
- BE-03: GET /api/projects/{projectId}/exports exposes durable export history metadata, and GET /api/projects/{projectId}/exports/{exportFileId} retrieves stored export content for authorized project members.
- BE-04: PROJECT_EXPORTED audit events are linked to the generated EXPORT_FILE, and export file persistence is rolled back if the linked audit event cannot be recorded.
- BE-05: Export file detail lookup is scoped by project_id and id, with cross-project requests covered by ProjectControllerTest.
- BE-07: Export history keeps large content out of paged lists while exposing content through a project-scoped detail endpoint.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%ExportTransactionTest%';
