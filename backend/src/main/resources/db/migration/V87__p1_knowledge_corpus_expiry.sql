alter table knowledge_document add column corpus_type varchar(32) not null default 'PROJECT_ARTIFACT';
alter table knowledge_document add column expires_at timestamp null;

update knowledge_document
set corpus_type = case
    when upper(artifact_type) in ('RESUME', 'RESUME_PROFILE') then 'RESUME'
    when upper(artifact_type) in ('QUESTION', 'QUESTION_BANK', 'INTERVIEW_QUESTION') then 'QUESTION'
    when upper(artifact_type) in ('RUBRIC', 'EVALUATION_RUBRIC') then 'RUBRIC'
    else 'PROJECT_ARTIFACT'
end
where corpus_type is null or corpus_type = 'PROJECT_ARTIFACT';

create index idx_knowledge_document_corpus_status_version
    on knowledge_document (project_id, corpus_type, status, artifact_version);
create index idx_knowledge_document_expiry_status
    on knowledge_document (expires_at, status);
