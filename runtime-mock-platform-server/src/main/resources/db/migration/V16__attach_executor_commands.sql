create table attach_executor_command (
    id varchar(64) primary key,
    executor_id varchar(64) not null references attach_executor(id),
    instance_id varchar(64) not null references instance(id),
    command_type varchar(64) not null,
    status varchar(64) not null,
    process_id varchar(128) not null,
    agent_jar varchar(512) not null,
    agent_args text not null,
    payload_json text not null,
    result_json text not null default '{}',
    error_message text not null default '',
    idempotency_key varchar(512) not null unique,
    attempt integer not null default 0,
    max_attempts integer not null default 3,
    lease_owner varchar(255),
    lease_expires_at timestamp,
    started_at timestamp,
    finished_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_attach_executor_command_poll
    on attach_executor_command(executor_id, status, lease_expires_at, created_at);

create index idx_attach_executor_command_instance
    on attach_executor_command(instance_id, created_at);

