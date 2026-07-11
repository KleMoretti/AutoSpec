update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, project history indexes):
- BE-07: Project history visibility queries now have idx_project_user_id_id and idx_project_member_user_id_project_id, covering legacy owner lookup and project_member joins used by GET /api/projects pagination.
- BE-08: SchemaInitSqlTest verifies the project history visibility indexes through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%project history indexes%';
