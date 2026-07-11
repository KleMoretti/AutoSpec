update artifact
set content = concat(content, '

Execution evidence update (2026-07-09, workflow run recovery index):
- BE-03: WorkflowRunService.timeoutRunningRunsBefore is backed by idx_workflow_run_status_created_at on (status, created_at), so stale RUNNING workflow recovery can find timeout candidates without scanning all workflow runs.
- BE-07: workflow_run recovery queries now have a status and created_at index aligned with the existing code_generation_job recovery path.
- BE-08: SchemaInitSqlTest verifies the workflow_run recovery index through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%workflow run recovery index%';
