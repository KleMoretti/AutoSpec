update artifact
set content = concat(content, '

Execution evidence update (2026-07-08, review issue diagnostics index):
- BE-07: review_issue diagnostics queries are backed by idx_review_issue_project_status_severity_id on (project_id, status, severity, id), covering project-scoped total/open/blocking/latest review issue inspection without scanning unrelated project findings.
- BE-08: SchemaInitSqlTest verifies the review_issue diagnostics index through Flyway on an H2 MySQL-compatible schema.
')
where type = 'ROADMAP_PLAN'
  and version = 5
  and title = 'AutoSpec Backend Engineering Depth Plan'
  and content not like '%review issue diagnostics index%';
