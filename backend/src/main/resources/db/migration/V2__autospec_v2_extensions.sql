alter table artifact add column status varchar(32) not null default 'GENERATED';
alter table artifact add column source_agent varchar(128) null;
alter table artifact add column parent_artifact_id bigint null;
alter table artifact add column approved_at timestamp null;
alter table artifact add column updated_at timestamp not null default current_timestamp;
create index idx_artifact_status on artifact (status);

alter table agent_task add column node_name varchar(128) null;
alter table agent_task add column duration_ms int null;
alter table agent_task add column retry_of_task_id bigint null;
alter table agent_task add column prompt_version_id bigint null;
create index idx_agent_task_node_name on agent_task (node_name);

create table if not exists prompt_version (
    id bigint primary key auto_increment,
    prompt_key varchar(128) not null,
    version varchar(32) not null,
    content longtext not null,
    checksum varchar(128) not null,
    active boolean not null default true,
    created_at timestamp not null default current_timestamp,
    constraint uk_prompt_version unique (prompt_key, version)
);
create index idx_prompt_version_active on prompt_version (prompt_key, active);

create table if not exists agent_event (
    id bigint primary key auto_increment,
    project_id bigint not null,
    task_id bigint null,
    event_type varchar(64) not null,
    node_name varchar(128) not null,
    message varchar(512) not null,
    payload longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_agent_event_project foreign key (project_id) references project (id),
    index idx_agent_event_project_id (project_id),
    index idx_agent_event_created_at (created_at)
);
