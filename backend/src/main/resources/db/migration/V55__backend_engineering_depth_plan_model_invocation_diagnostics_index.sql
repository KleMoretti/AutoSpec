update artifact
set content = concat(content, '

Execution evidence update (2026-07-08, model invocation diagnostics index):
- BE-07: model_invocation diagnostics queries are backed by idx_model_invocation_project_status_id on (project_id, status, id), covering project-scoped model call counts and latest failed invocation inspection without scanning unrelated project telemetry.
- BE-08: SchemaInitSqlTest verifies the model_invocation diagnostics index through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%model invocation diagnostics index%';
