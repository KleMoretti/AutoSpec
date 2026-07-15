alter table workflow_node_run add column duration_ms int null;
alter table workflow_run add column accepted_duplicate_event_count int not null default 0;
