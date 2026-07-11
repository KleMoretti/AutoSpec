update artifact
set content = concat(content, '

Execution evidence update (2026-07-09, code generation cancellation audit):
- BE-04: POST /api/projects/{projectId}/code-generation-jobs/{jobId}/cancel now records a CODE_GENERATION_JOB_CANCELLED audit event with actor user, CODE_GENERATION_JOB entity id, message, and cancellation metadata.
- BE-05: The audit event is emitted only after the OWNER/EDITOR authorization check, so code generation cancellation audit evidence follows the same project member boundary as the job state transition.
- BE-08: ProjectControllerTest verifies the cancelled code generation job response and the externally visible audit-events response in the same HTTP regression.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%code generation cancellation audit%';
