alter table code_generation_job add column retry_of_job_id bigint null;

create index idx_code_generation_job_retry_of_job_id on code_generation_job (retry_of_job_id);
