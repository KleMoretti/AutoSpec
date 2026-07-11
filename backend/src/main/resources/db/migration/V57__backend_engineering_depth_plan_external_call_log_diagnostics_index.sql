update artifact
set content = concat(content, '

Execution evidence update (2026-07-08, external call log diagnostics index):
- BE-07: external_call_log diagnostics queries are backed by idx_external_call_log_project_status_id on (project_id, status, id), covering project-scoped external call counts and latest failed Agent Engine call inspection without scanning unrelated integration telemetry.
- BE-08: SchemaInitSqlTest verifies the external_call_log diagnostics index through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%external call log diagnostics index%';
