create table if not exists workflow_execution_bundle (
    id bigint primary key auto_increment,
    workflow_version_id bigint not null,
    bundle_version varchar(32) not null,
    bundle_json longtext not null,
    bundle_hash varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_workflow_execution_bundle_version
        foreign key (workflow_version_id) references workflow_version (id),
    constraint uk_workflow_execution_bundle_version
        unique (workflow_version_id, bundle_version),
    constraint uk_workflow_execution_bundle_hash unique (bundle_hash),
    index idx_workflow_execution_bundle_version (workflow_version_id, id)
);

alter table workflow_run add column execution_bundle_id bigint null;
alter table workflow_run add column execution_bundle_hash varchar(64) null;
alter table workflow_run add column execution_bundle_json longtext null;

create index idx_workflow_run_execution_bundle
    on workflow_run (execution_bundle_id, execution_bundle_hash);
