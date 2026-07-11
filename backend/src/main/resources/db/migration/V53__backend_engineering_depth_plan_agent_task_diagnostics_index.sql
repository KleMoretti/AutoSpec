update artifact
set content = concat(content, '

Execution evidence update (2026-07-08, agent task diagnostics index):
- BE-07: agent_task diagnostics queries are backed by idx_agent_task_project_status_id on (project_id, status, id), covering project-scoped task counts and latest failed Agent node inspection without scanning unrelated project task rows.
- BE-08: SchemaInitSqlTest verifies the agent_task diagnostics index through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%agent task diagnostics index%';
