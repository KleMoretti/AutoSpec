create index idx_review_issue_project_status_severity_id
    on review_issue (project_id, status, severity, id);
