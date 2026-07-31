alter table workflow_outbox add column last_error_type varchar(128) null;
alter table workflow_outbox add column last_error_at timestamp null;
alter table workflow_outbox add column dead_lettered_at timestamp null;
alter table workflow_outbox add column closed_at timestamp null;
