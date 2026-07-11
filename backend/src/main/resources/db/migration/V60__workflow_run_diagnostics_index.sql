create index idx_workflow_run_project_status_id
    on workflow_run (project_id, status, id);
