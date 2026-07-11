alter table model_invocation add column workflow_run_id bigint null;

alter table model_invocation
    add constraint fk_model_invocation_workflow_run
        foreign key (workflow_run_id) references workflow_run (id);

create index idx_model_invocation_workflow_run_id on model_invocation (workflow_run_id);
