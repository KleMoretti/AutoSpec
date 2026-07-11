update artifact
set content = concat(content, '

Execution evidence update (2026-07-07, workflow cancellation audit):
- BE-04: POST /api/projects/{projectId}/workflow-runs/{runId}/cancel now records a WORKFLOW_RUN_CANCELLED audit event with actor user, workflow run entity id, correlation id, message, and cancellation metadata.
- BE-05: The audit event is emitted only after the OWNER/EDITOR authorization check, so cancellation audit evidence follows the same tenant boundary as the workflow run state transition.
- BE-08: ProjectControllerTest verifies cancellation state change and the externally visible audit-events response in the same HTTP regression.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%workflow cancellation audit%';
