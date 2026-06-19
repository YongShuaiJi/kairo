create table platform_access_token (
    id varchar(64) primary key,
    token_hash varchar(128) not null unique,
    subject_type varchar(32) not null,
    subject_id varchar(255) not null,
    display_name varchar(255) not null,
    status varchar(32) not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    expires_at timestamp not null,
    last_used_at timestamp,
    revoked_at timestamp
);

create index idx_platform_access_token_subject
    on platform_access_token(subject_type, subject_id, status);
create index idx_platform_access_token_expiry
    on platform_access_token(status, expires_at);
