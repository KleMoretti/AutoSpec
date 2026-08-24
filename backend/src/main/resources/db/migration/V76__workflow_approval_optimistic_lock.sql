alter table workflow_approval add column lock_version int not null default 0;
