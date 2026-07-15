alter table workflow_node_run
    add column if not exists timeout_ms int not null default 30000;
