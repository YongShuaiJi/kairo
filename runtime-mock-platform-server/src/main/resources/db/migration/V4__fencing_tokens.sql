create table fencing_token (
    id varchar(64) primary key,
    resource_type varchar(64) not null,
    resource_id varchar(128) not null,
    purpose varchar(128) not null,
    token varchar(255) not null unique,
    sequence bigint not null,
    owner varchar(255) not null,
    status varchar(64) not null,
    lease_expires_at timestamp not null,
    created_at timestamp not null,
    consumed_at timestamp,
    correlation_id varchar(128) not null
);

create index idx_fencing_token_resource on fencing_token(resource_type, resource_id, status, lease_expires_at);

create table fencing_sequence (
    resource_key varchar(255) primary key,
    current_value bigint not null,
    updated_at timestamp not null
);
