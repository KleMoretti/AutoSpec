alter table workflow_node_run add column contract_hash varchar(64) null;
alter table workflow_node_run add column fencing_token bigint not null default 0;

create index idx_workflow_node_run_contract_fence
    on workflow_node_run (execution_id, contract_hash, fencing_token);

alter table model_invocation add column prompt_version varchar(64) null;
alter table model_invocation add column prompt_checksum varchar(64) null;
alter table model_invocation add column contract_hash varchar(64) null;
