alter table knowledge_document add column content_hash varchar(64) null;
alter table knowledge_document add column chunker_version varchar(64) null;
alter table knowledge_document add column embedding_model varchar(128) null;
alter table knowledge_document add column failure_message varchar(1024) null;
alter table knowledge_document add column updated_at timestamp null;
alter table knowledge_document add column activated_at timestamp null;
alter table knowledge_document add column superseded_at timestamp null;

update knowledge_document
set status = 'ACTIVE',
    content_hash = (
        select artifact.content_hash
        from artifact
        where artifact.id = knowledge_document.artifact_id
    ),
    chunker_version = 'structured-text-900-120-v1',
    embedding_model = 'autospec-hashing-ngram-v1',
    activated_at = created_at,
    updated_at = created_at
where status = 'INDEXED';

update knowledge_document
set status = 'SUPERSEDED',
    activated_at = null,
    superseded_at = updated_at
where id in (
    select ranked.id
    from (
        select id,
               row_number() over (
                   partition by project_id, artifact_type
                   order by artifact_version desc, id desc
               ) as version_rank
        from knowledge_document
        where status = 'ACTIVE'
    ) ranked
    where ranked.version_rank > 1
);

create index idx_knowledge_document_active_version
    on knowledge_document (project_id, artifact_type, status, artifact_version);
