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

create table if not exists user_account (
    id bigint primary key auto_increment,
    username varchar(128) not null,
    display_name varchar(128) not null,
    password_hash varchar(256) not null,
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_user_account_username unique (username)
);

create table if not exists project_member (
    id bigint primary key auto_increment,
    project_id bigint not null,
    user_id bigint not null,
    role varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_project_member_project foreign key (project_id) references project (id),
    constraint fk_project_member_user foreign key (user_id) references user_account (id),
    constraint uk_project_member unique (project_id, user_id),
    index idx_project_member_project_id (project_id),
    index idx_project_member_user_id (user_id)
);

create table if not exists knowledge_document (
    id bigint primary key auto_increment,
    project_id bigint not null,
    artifact_id bigint not null,
    artifact_type varchar(64) not null,
    artifact_version int not null,
    title varchar(256) not null,
    status varchar(32) not null default 'INDEXED',
    created_at timestamp not null default current_timestamp,
    constraint fk_knowledge_document_project foreign key (project_id) references project (id),
    constraint fk_knowledge_document_artifact foreign key (artifact_id) references artifact (id),
    constraint uk_knowledge_document_artifact unique (artifact_id),
    index idx_knowledge_document_project_id (project_id)
);

create table if not exists knowledge_chunk (
    id bigint primary key auto_increment,
    document_id bigint not null,
    chunk_index int not null,
    content text not null,
    token_hint int not null default 0,
    retrieval_terms varchar(512) not null,
    vector_ref varchar(256) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_knowledge_chunk_document foreign key (document_id) references knowledge_document (id),
    constraint uk_knowledge_chunk unique (document_id, chunk_index),
    index idx_knowledge_chunk_terms (retrieval_terms)
);

create table if not exists model_provider (
    id bigint primary key auto_increment,
    provider_key varchar(64) not null,
    display_name varchar(128) not null,
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    constraint uk_model_provider_key unique (provider_key)
);

create table if not exists model_config (
    id bigint primary key auto_increment,
    provider_key varchar(64) not null,
    model_name varchar(128) not null,
    agent_node varchar(128) not null,
    priority int not null default 100,
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    constraint uk_model_config unique (provider_key, model_name, agent_node),
    index idx_model_config_node (agent_node, enabled, priority)
);

create table if not exists model_invocation (
    id bigint primary key auto_increment,
    project_id bigint not null,
    task_id bigint null,
    provider_key varchar(64) not null,
    model_name varchar(128) not null,
    agent_node varchar(128) not null,
    prompt_version_id bigint null,
    status varchar(32) not null,
    duration_ms int not null default 0,
    input_tokens int not null default 0,
    output_tokens int not null default 0,
    estimated_cost decimal(12, 6) null,
    score decimal(5, 2) null,
    error_message text null,
    created_at timestamp not null default current_timestamp,
    constraint fk_model_invocation_project foreign key (project_id) references project (id),
    index idx_model_invocation_project_id (project_id),
    index idx_model_invocation_agent_node (agent_node)
);

create table if not exists code_generation_job (
    id bigint primary key auto_increment,
    project_id bigint not null,
    status varchar(32) not null,
    manifest longtext null,
    error_message text null,
    created_at timestamp not null default current_timestamp,
    completed_at timestamp null,
    constraint fk_code_generation_job_project foreign key (project_id) references project (id),
    index idx_code_generation_job_project_id (project_id)
);

create table if not exists export_file (
    id bigint primary key auto_increment,
    project_id bigint not null,
    job_id bigint null,
    file_name varchar(256) not null,
    media_type varchar(128) not null,
    encoding varchar(32) not null,
    content longtext not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_export_file_project foreign key (project_id) references project (id),
    index idx_export_file_project_id (project_id)
);

create table if not exists workflow_snapshot (
    id bigint primary key auto_increment,
    project_id bigint not null,
    workflow_key varchar(128) not null,
    version varchar(32) not null,
    graph_json longtext not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_workflow_snapshot_project foreign key (project_id) references project (id),
    index idx_workflow_snapshot_project_id (project_id)
);
