create table if not exists audit_event (
    id bigint primary key auto_increment,
    project_id bigint not null,
    actor_user_id bigint null,
    event_type varchar(64) not null,
    entity_type varchar(64) not null,
    entity_id bigint null,
    message varchar(512) not null,
    metadata longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_audit_event_project foreign key (project_id) references project (id),
    constraint fk_audit_event_actor_user foreign key (actor_user_id) references user_account (id),
    index idx_audit_event_project_id (project_id),
    index idx_audit_event_type (event_type),
    index idx_audit_event_entity (entity_type, entity_id)
);
