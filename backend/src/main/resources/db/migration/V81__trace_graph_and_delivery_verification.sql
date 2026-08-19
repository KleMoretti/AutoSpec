create table artifact_component (
    id bigint primary key auto_increment,
    project_id bigint not null,
    workflow_run_id bigint null,
    artifact_id bigint not null,
    artifact_type varchar(64) not null,
    component_key varchar(128) not null,
    component_type varchar(64) not null,
    display_name varchar(512) not null,
    json_path varchar(512) not null,
    content_hash varchar(64) not null,
    created_at datetime not null default current_timestamp,
    constraint uk_artifact_component_key unique (artifact_id, component_key),
    constraint fk_artifact_component_project foreign key (project_id) references project(id),
    constraint fk_artifact_component_artifact foreign key (artifact_id) references artifact(id)
);

create index idx_artifact_component_project_run
    on artifact_component(project_id, workflow_run_id, component_type);
create index idx_artifact_component_key
    on artifact_component(project_id, component_key);

create table artifact_trace_edge (
    id bigint primary key auto_increment,
    project_id bigint not null,
    workflow_run_id bigint null,
    source_artifact_id bigint not null,
    from_component_key varchar(128) not null,
    to_component_key varchar(128) not null,
    relation_type varchar(64) not null,
    created_at datetime not null default current_timestamp,
    constraint uk_artifact_trace_edge unique (
        source_artifact_id,
        from_component_key,
        to_component_key,
        relation_type
    ),
    constraint fk_artifact_trace_project foreign key (project_id) references project(id),
    constraint fk_artifact_trace_artifact foreign key (source_artifact_id) references artifact(id)
);

create index idx_artifact_trace_project_run
    on artifact_trace_edge(project_id, workflow_run_id, relation_type);
create index idx_artifact_trace_target
    on artifact_trace_edge(project_id, to_component_key);

alter table code_generation_job add column gate_status varchar(32) null;
alter table code_generation_job add column manifest_hash varchar(64) null;
alter table code_generation_job add column verification_json longtext null;
alter table code_generation_job add column verified_at datetime null;

create index idx_code_generation_delivery_gate
    on code_generation_job(project_id, gate_status, id);
