create index idx_code_generation_job_project_status_id
    on code_generation_job (project_id, status, id);
