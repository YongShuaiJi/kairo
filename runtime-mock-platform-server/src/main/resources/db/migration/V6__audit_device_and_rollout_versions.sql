alter table audit_record add column device varchar(512) not null default '';

alter table rollout_batch add column version bigint not null default 1;
alter table rollout_batch add column updated_by varchar(255) not null default 'system';

alter table rollout_instance_execution add column version bigint not null default 1;
alter table rollout_instance_execution add column updated_by varchar(255) not null default 'system';

create table idempotency_record (
    idempotency_key varchar(255) primary key,
    actor varchar(255) not null,
    request_hash varchar(128) not null,
    response_status integer not null,
    response_json text not null,
    created_at timestamp not null,
    expires_at timestamp not null
);

create index idx_idempotency_expires_at on idempotency_record(expires_at);

create table scoped_counter (
    counter_key varchar(512) primary key,
    current_value bigint not null,
    updated_at timestamp not null
);
