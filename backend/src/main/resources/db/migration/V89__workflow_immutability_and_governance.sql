alter table user_account add column platform_role varchar(32) not null default 'PROJECT_USER';

alter table workflow_version add column immutable_at timestamp null;

update workflow_version
set immutable_at = published_at
where status = 'PUBLISHED'
  and immutable_at is null;

create index idx_workflow_version_immutable_at
    on workflow_version (status, immutable_at, id);
