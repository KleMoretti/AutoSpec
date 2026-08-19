alter table model_invocation add column route_key varchar(64) null;
alter table model_invocation add column route_reason varchar(512) null;
alter table model_invocation add column fallback_used boolean not null default false;
alter table model_invocation add column context_manifest_json longtext null;
