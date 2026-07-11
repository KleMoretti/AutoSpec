create index idx_model_invocation_project_status_id
    on model_invocation (project_id, status, id);
