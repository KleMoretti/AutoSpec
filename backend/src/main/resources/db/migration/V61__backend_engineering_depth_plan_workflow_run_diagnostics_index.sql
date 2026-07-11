update artifact
set content = concat(content, '

Execution evidence update (2026-07-08, workflow run diagnostics index):
- BE-07: workflow_run diagnostics queries are backed by idx_workflow_run_project_status_id on (project_id, status, id), covering project-scoped run state counts and latest failed workflow inspection without scanning unrelated project runs.
- BE-08: SchemaInitSqlTest verifies the workflow_run diagnostics index through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%workflow run diagnostics index%';
