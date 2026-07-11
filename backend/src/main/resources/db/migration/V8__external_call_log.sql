create table if not exists external_call_log (
    id bigint primary key auto_increment,
    project_id bigint not null,
    target_service varchar(128) not null,
    operation varchar(128) not null,
    status varchar(32) not null,
    duration_ms int not null default 0,
    request_context longtext null,
    error_message text null,
    started_at timestamp not null,
    completed_at timestamp not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_external_call_log_project foreign key (project_id) references project (id),
    index idx_external_call_log_project_id (project_id),
    index idx_external_call_log_operation (operation),
    index idx_external_call_log_status (status)
);
