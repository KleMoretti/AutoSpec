update artifact
set content = concat(content, '

Execution evidence update (2026-07-08, code generation job diagnostics index):
- BE-07: code_generation_job diagnostics queries are backed by idx_code_generation_job_project_status_id on (project_id, status, id), covering project-scoped job state counts and latest failed code export inspection without scanning unrelated project jobs.
- BE-08: SchemaInitSqlTest verifies the code_generation_job diagnostics index through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%code generation job diagnostics index%';
