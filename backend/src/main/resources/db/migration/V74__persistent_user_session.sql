create table if not exists user_session (
    id bigint primary key auto_increment,
    token_hash varchar(64) not null,
    user_id bigint not null,
    expires_at timestamp not null,
    revoked_at timestamp null,
    created_at timestamp not null default current_timestamp,
    constraint uk_user_session_token_hash unique (token_hash),
    constraint fk_user_session_user foreign key (user_id) references user_account (id),
    index idx_user_session_user_active (user_id, revoked_at, expires_at),
    index idx_user_session_expiry (expires_at)
);
