alter table workflow_run add column reserved_tokens bigint not null default 0;
alter table workflow_run add column reserved_cost decimal(14, 6) not null default 0;
alter table workflow_run add column reserved_model_calls int not null default 0;

alter table workflow_node_run add column budget_reservation_id varchar(255) null;
alter table workflow_node_run add column reserved_input_tokens bigint not null default 0;
alter table workflow_node_run add column reserved_output_tokens bigint not null default 0;
alter table workflow_node_run add column reserved_cost decimal(14, 6) not null default 0;
alter table workflow_node_run add column reserved_model_calls int not null default 0;
alter table workflow_node_run add column actual_input_tokens int not null default 0;
alter table workflow_node_run add column actual_output_tokens int not null default 0;
alter table workflow_node_run add column actual_cache_tokens int not null default 0;
alter table workflow_node_run add column actual_cost decimal(14, 6) not null default 0;
alter table workflow_node_run add column actual_model_calls int not null default 0;
alter table workflow_node_run add column actual_tool_calls int not null default 0;
alter table workflow_node_run add column budget_status varchar(32) null;
alter table workflow_node_run add column budget_settled_at timestamp null;

create index idx_workflow_node_run_budget_reservation
    on workflow_node_run (budget_reservation_id, budget_status);

alter table model_invocation add column call_id varchar(255) null;
alter table model_invocation add column call_type varchar(16) not null default 'MODEL';
alter table model_invocation add column execution_id varchar(255) null;
alter table model_invocation add column call_sequence int null;
alter table model_invocation add column attempt int null;
alter table model_invocation add column schema_version varchar(128) null;
alter table model_invocation add column reserved_input_tokens int not null default 0;
alter table model_invocation add column reserved_output_tokens int not null default 0;
alter table model_invocation add column reserved_cost decimal(14, 6) not null default 0;
alter table model_invocation add column settlement_delta_tokens int not null default 0;
alter table model_invocation add column settlement_delta_cost decimal(14, 6) not null default 0;
alter table model_invocation add column normalized_params_hash varchar(64) null;
alter table model_invocation add column result_hash varchar(64) null;
alter table model_invocation add column error_code varchar(128) null;
alter table model_invocation add column deadline_epoch_ms bigint null;
alter table model_invocation add column idempotency_key varchar(255) null;
alter table model_invocation add column tool_name varchar(128) null;
alter table model_invocation add column tool_version varchar(64) null;
alter table model_invocation add column permission_policy varchar(128) null;
alter table model_invocation add column reference_sources_json longtext null;
alter table model_invocation add column redacted_params_json longtext null;

create unique index uk_model_invocation_call_id on model_invocation (call_id);
create index idx_model_invocation_execution_sequence
    on model_invocation (execution_id, call_sequence);
create index idx_model_invocation_call_type_status
    on model_invocation (call_type, status, id);
