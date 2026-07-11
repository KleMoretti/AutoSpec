update artifact
set content = concat(content, '

Execution evidence update (2026-07-05, workflow run history index):
- BE-07: Workflow run history queries now have idx_workflow_run_project_id_id, covering GET /api/projects/{projectId}/workflow-runs pagination by project and run id.
- BE-08: SchemaInitSqlTest verifies the workflow_run(project_id, id) index through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%workflow run history index%';
