alter table knowledge_chunk add column content_hash varchar(64) null;
alter table knowledge_chunk add column embedding_model varchar(128) null;
alter table knowledge_chunk add column embedding_dimensions int null;
alter table knowledge_chunk add column embedding_json longtext null;

create index idx_knowledge_chunk_content_hash
    on knowledge_chunk (content_hash);
