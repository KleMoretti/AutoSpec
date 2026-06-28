create table if not exists project (
    id bigint primary key auto_increment,
    user_id bigint not null,
    name varchar(128) not null,
    original_requirement longtext not null,
    status varchar(32) not null default 'CREATED',
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    index idx_project_user_id (user_id)
);

create table if not exists agent_task (
    id bigint primary key auto_increment,
    project_id bigint not null,
    agent_name varchar(128) not null,
    status varchar(32) not null,
    node_name varchar(128),
    input_text longtext,
    output_text longtext,
    error_message text,
    duration_ms int,
    retry_of_task_id bigint,
    prompt_version_id bigint,
    start_time timestamp null,
    end_time timestamp null,
    created_at timestamp not null default current_timestamp,
    constraint fk_agent_task_project foreign key (project_id) references project (id),
    index idx_agent_task_project_id (project_id),
    index idx_agent_task_agent_name (agent_name),
    index idx_agent_task_node_name (node_name)
);

create table if not exists artifact (
    id bigint primary key auto_increment,
    project_id bigint not null,
    type varchar(64) not null,
    title varchar(256) not null,
    content longtext not null,
    format varchar(32) not null,
    version int not null default 1,
    status varchar(32) not null default 'GENERATED',
    source_agent varchar(128),
    parent_artifact_id bigint,
    approved_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_artifact_project foreign key (project_id) references project (id),
    index idx_artifact_project_id (project_id),
    index idx_artifact_type (type),
    index idx_artifact_status (status)
);

create table if not exists review_issue (
    id bigint primary key auto_increment,
    project_id bigint not null,
    severity varchar(32) not null,
    issue_type varchar(64) not null,
    description text not null,
    suggestion text,
    status varchar(32) not null default 'OPEN',
    created_at timestamp not null default current_timestamp,
    constraint fk_review_issue_project foreign key (project_id) references project (id),
    index idx_review_issue_project_id (project_id),
    index idx_review_issue_status (status)
);

create table if not exists prompt_version (
    id bigint primary key auto_increment,
    prompt_key varchar(128) not null,
    version varchar(32) not null,
    content longtext not null,
    checksum varchar(128) not null,
    active boolean not null default true,
    created_at timestamp not null default current_timestamp,
    constraint uk_prompt_version unique (prompt_key, version),
    index idx_prompt_version_active (prompt_key, active)
);

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
