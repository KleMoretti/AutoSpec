create table if not exists workflow_definition (
    id bigint primary key auto_increment,
    workflow_key varchar(128) not null,
    name varchar(255) not null,
    description text,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_workflow_definition_key unique (workflow_key)
);

create table if not exists workflow_version (
    id bigint primary key auto_increment,
    definition_id bigint not null,
    version varchar(64) not null,
    spec_json text not null,
    content_hash varchar(128) not null,
    status varchar(32) not null,
    published_at timestamp null,
    created_at timestamp not null default current_timestamp,
    constraint fk_workflow_version_definition foreign key (definition_id) references workflow_definition (id),
    constraint uk_workflow_version_definition_version unique (definition_id, version),
    constraint uk_workflow_version_content_hash unique (content_hash),
    index idx_workflow_version_definition_id (definition_id)
);

alter table workflow_run add column if not exists workflow_version_id bigint null;
alter table workflow_run add column if not exists workflow_snapshot_json text null;
alter table workflow_run add column if not exists replay_of_run_id bigint null;
alter table workflow_run add column if not exists review_round int not null default 0;
alter table workflow_run add column if not exists max_review_rounds int not null default 0;
alter table workflow_run add column if not exists lock_version int not null default 0;
alter table workflow_run add column if not exists last_heartbeat_at timestamp null;
create index if not exists idx_workflow_run_workflow_version_id on workflow_run (workflow_version_id);
create index if not exists idx_workflow_run_replay_of_run_id on workflow_run (replay_of_run_id);

create table if not exists workflow_node_run (
    id bigint primary key auto_increment,
    workflow_run_id bigint not null,
    node_id varchar(128) not null,
    revision int not null default 1,
    attempt int not null default 1,
    execution_id varchar(255) not null,
    status varchar(32) not null,
    handler_key varchar(128) not null,
    handler_version varchar(64) not null,
    input_json text,
    output_json text,
    error_code varchar(64),
    error_message text,
    queued_at timestamp null,
    started_at timestamp null,
    heartbeat_at timestamp null,
    finished_at timestamp null,
    next_retry_at timestamp null,
    worker_id varchar(128),
    lock_version int not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_workflow_node_run_workflow_run foreign key (workflow_run_id) references workflow_run (id),
    constraint uk_workflow_node_run_revision_attempt unique (workflow_run_id, node_id, revision, attempt),
    constraint uk_workflow_node_run_execution_id unique (execution_id),
    index idx_workflow_node_run_workflow_run_id (workflow_run_id),
    index idx_workflow_node_run_recovery (status, heartbeat_at, next_retry_at)
);

create table if not exists workflow_approval (
    id bigint primary key auto_increment,
    workflow_run_id bigint not null,
    node_run_id bigint not null,
    mode varchar(32) not null,
    status varchar(32) not null,
    decision varchar(32),
    candidate_artifact_id bigint null,
    revised_artifact_id bigint null,
    decided_by_user_id bigint null,
    decision_reason text,
    idempotency_key varchar(128) not null,
    decided_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_workflow_approval_run foreign key (workflow_run_id) references workflow_run (id),
    constraint fk_workflow_approval_node_run foreign key (node_run_id) references workflow_node_run (id),
    constraint uk_workflow_approval_idempotency unique (workflow_run_id, idempotency_key),
    index idx_workflow_approval_run_status (workflow_run_id, status)
);

create table if not exists workflow_transition (
    id bigint primary key auto_increment,
    workflow_run_id bigint not null,
    node_run_id bigint null,
    from_status varchar(32),
    to_status varchar(32) not null,
    event_type varchar(64) not null,
    event_id varchar(128),
    metadata_json text,
    created_at timestamp not null default current_timestamp,
    constraint fk_workflow_transition_run foreign key (workflow_run_id) references workflow_run (id),
    constraint fk_workflow_transition_node_run foreign key (node_run_id) references workflow_node_run (id),
    index idx_workflow_transition_run_id (workflow_run_id, id)
);

create table if not exists workflow_outbox (
    id bigint primary key auto_increment,
    event_id varchar(128) not null,
    aggregate_id varchar(128) not null,
    event_type varchar(64) not null,
    payload_json text not null,
    status varchar(32) not null,
    retry_count int not null default 0,
    next_retry_at timestamp null,
    published_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_workflow_outbox_event_id unique (event_id),
    index idx_workflow_outbox_publish (status, next_retry_at, id)
);

create table if not exists processed_workflow_event (
    id bigint primary key auto_increment,
    event_id varchar(128) not null,
    event_type varchar(64) not null,
    processed_at timestamp not null default current_timestamp,
    constraint uk_processed_workflow_event_id unique (event_id)
);

alter table agent_task add column if not exists workflow_node_run_id bigint null;
alter table agent_event add column if not exists workflow_node_run_id bigint null;
alter table artifact add column if not exists workflow_node_run_id bigint null;
alter table model_invocation add column if not exists workflow_node_run_id bigint null;

create index if not exists idx_agent_task_workflow_node_run_id on agent_task (workflow_node_run_id);
create index if not exists idx_agent_event_workflow_node_run_id on agent_event (workflow_node_run_id);
create index if not exists idx_artifact_workflow_node_run_id on artifact (workflow_node_run_id);
create index if not exists idx_model_invocation_workflow_node_run_id on model_invocation (workflow_node_run_id);
