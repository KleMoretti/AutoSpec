create index idx_workflow_run_status_created_at
    on workflow_run (status, created_at);
