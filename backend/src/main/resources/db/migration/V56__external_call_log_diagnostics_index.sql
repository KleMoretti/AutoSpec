create index idx_external_call_log_project_status_id
    on external_call_log (project_id, status, id);
