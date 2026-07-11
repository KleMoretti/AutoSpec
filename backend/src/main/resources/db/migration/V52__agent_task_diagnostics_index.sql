create index idx_agent_task_project_status_id
    on agent_task (project_id, status, id);
