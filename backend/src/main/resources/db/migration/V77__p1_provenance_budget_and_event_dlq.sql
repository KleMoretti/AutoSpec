alter table artifact add column content_hash varchar(64) null;
alter table artifact add column schema_version varchar(32) not null default 'v1';
alter table artifact add column prompt_key varchar(128) null;
alter table artifact add column prompt_version varchar(64) null;
alter table artifact add column model_provider varchar(64) null;
alter table artifact add column model_name varchar(128) null;
alter table artifact add column source_citations_json longtext null;
alter table artifact add column provenance_json longtext null;

create index idx_artifact_project_content_hash
    on artifact (project_id, content_hash);

alter table review_issue add column issue_key varchar(128) null;
alter table review_issue add column artifact_type varchar(64) null;
alter table review_issue add column artifact_path varchar(256) null;
alter table review_issue add column requirement_id varchar(128) null;
alter table review_issue add column evidence text null;
alter table review_issue add column owner_user_id bigint null;
alter table review_issue add column resolution text null;
alter table review_issue add column resolved_in_artifact_id bigint null;
alter table review_issue add column updated_at timestamp null default current_timestamp;

create index idx_review_issue_project_key
    on review_issue (project_id, issue_key);

alter table workflow_run add column quality_profile varchar(32) not null default 'BALANCED';
alter table workflow_run add column max_tokens bigint null;
alter table workflow_run add column max_cost decimal(14, 6) null;
alter table workflow_run add column max_model_calls int null;
alter table workflow_run add column max_wall_time_ms bigint null;
alter table workflow_run add column consumed_tokens bigint not null default 0;
alter table workflow_run add column consumed_cost decimal(14, 6) not null default 0;
alter table workflow_run add column model_call_count int not null default 0;

alter table model_invocation add column cache_tokens int not null default 0;
alter table model_invocation add column prompt_key varchar(128) null;
alter table model_invocation add column call_count int not null default 1;

create table if not exists workflow_event_dead_letter (
    id bigint primary key auto_increment,
    stream_message_id varchar(128) not null,
    payload_json longtext not null,
    error_type varchar(128) not null,
    error_message text null,
    status varchar(32) not null default 'OPEN',
    replayed_at timestamp null,
    closed_at timestamp null,
    created_at timestamp not null default current_timestamp,
    constraint uk_workflow_event_dead_letter_message unique (stream_message_id),
    index idx_workflow_event_dead_letter_status_id (status, id)
);
