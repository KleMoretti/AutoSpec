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
    input_text longtext,
    output_text longtext,
    error_message text,
    start_time timestamp null,
    end_time timestamp null,
    created_at timestamp not null default current_timestamp,
    constraint fk_agent_task_project foreign key (project_id) references project (id),
    index idx_agent_task_project_id (project_id),
    index idx_agent_task_agent_name (agent_name)
);

create table if not exists artifact (
    id bigint primary key auto_increment,
    project_id bigint not null,
    type varchar(64) not null,
    title varchar(256) not null,
    content longtext not null,
    format varchar(32) not null,
    version int not null default 1,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_project foreign key (project_id) references project (id),
    index idx_artifact_project_id (project_id),
    index idx_artifact_type (type)
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
