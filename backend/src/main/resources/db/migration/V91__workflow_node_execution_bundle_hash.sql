alter table workflow_node_run add column execution_bundle_hash varchar(64) null;

create index idx_workflow_node_run_bundle_hash
    on workflow_node_run(execution_bundle_hash, workflow_run_id, node_id);
