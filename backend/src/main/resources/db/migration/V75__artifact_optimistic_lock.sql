alter table artifact add column lock_version int not null default 0;

alter table artifact
    add constraint uk_artifact_project_type_version unique (project_id, type, version);
