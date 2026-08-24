create index idx_workflow_outbox_aggregate_status_id
    on workflow_outbox (aggregate_id, status, id);
