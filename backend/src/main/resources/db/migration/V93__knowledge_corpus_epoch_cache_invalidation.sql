create table if not exists knowledge_corpus_epoch (
    id bigint primary key auto_increment,
    project_id bigint not null,
    corpus_epoch bigint not null default 1,
    access_policy_version varchar(128) not null,
    last_invalidation_reason varchar(128) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_knowledge_corpus_epoch_project unique (project_id),
    index idx_knowledge_corpus_epoch_updated (updated_at)
);
