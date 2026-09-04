alter table workflow_run add column initiated_by_user_id bigint null;

create index idx_workflow_run_initiated_user_status
    on workflow_run (initiated_by_user_id, status, created_at);
