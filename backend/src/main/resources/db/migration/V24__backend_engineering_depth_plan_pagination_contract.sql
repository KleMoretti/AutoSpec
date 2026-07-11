update artifact
set content = concat(content, '

Execution evidence update (2026-07-04, pagination contract):
- BE-06: PaginationRequest centralizes REST list parameter defaults and validation for limit/offset while preserving the stable ApiErrorResponse path through ResponseStatusException; PaginationRequestTest covers defaults, boundary values, and BAD_REQUEST error messages.
- BE-07: Artifact, artifact version, review issue, Agent event, workflow run, audit event, model invocation, code generation job, export file, and external call history endpoints now share the same PaginationRequest contract, reducing query-boundary drift across history APIs.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%PaginationRequestTest%';
