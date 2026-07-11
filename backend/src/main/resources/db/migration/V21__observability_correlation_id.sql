alter table workflow_run add column correlation_id varchar(64) null;
alter table audit_event add column correlation_id varchar(64) null;
alter table external_call_log add column correlation_id varchar(64) null;
alter table model_invocation add column correlation_id varchar(64) null;

create index idx_workflow_run_correlation_id on workflow_run (correlation_id);
create index idx_audit_event_project_correlation_id on audit_event (project_id, correlation_id);
create index idx_external_call_log_project_correlation_id on external_call_log (project_id, correlation_id);
create index idx_model_invocation_project_correlation_id on model_invocation (project_id, correlation_id);
