update artifact
set content = concat(content, '

Execution evidence update (2026-07-06, diagnostics authorization):
- BE-05: GET /api/projects/{projectId}/diagnostics is now covered by the cross-project non-member denial regression, so project-level operational telemetry follows the same owner/editor/viewer boundary as workflow, audit, model, external-call, and code-generation history.
- BE-08: ProjectControllerTest verifies diagnostics tenant isolation together with the rest of the observability and retry endpoints.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%diagnostics authorization%';
