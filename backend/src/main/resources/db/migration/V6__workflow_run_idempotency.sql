create table if not exists workflow_run (
    id bigint primary key auto_increment,
    project_id bigint not null,
    operation varchar(64) not null,
    idempotency_key varchar(128) not null,
    status varchar(32) not null,
    response_status varchar(32),
    response_percent int,
    error_message text,
    started_at timestamp not null default current_timestamp,
    completed_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_workflow_run_project foreign key (project_id) references project (id),
    constraint uk_workflow_run_idempotency unique (project_id, operation, idempotency_key),
    index idx_workflow_run_project_id (project_id),
    index idx_workflow_run_status (status)
);
