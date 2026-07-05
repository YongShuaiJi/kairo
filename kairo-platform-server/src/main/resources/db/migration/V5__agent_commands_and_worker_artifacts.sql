create table agent_command (
    id varchar(64) primary key,
    agent_id varchar(64) not null references agent_instance(id),
    command_type varchar(64) not null,
    status varchar(64) not null,
    idempotency_key varchar(255) not null,
    payload_json text not null,
    result_json text not null,
    attempts integer not null default 0,
    max_attempts integer not null default 5,
    available_at timestamp not null,
    lease_expires_at timestamp,
    dispatched_at timestamp,
    completed_at timestamp,
    error_message text,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    correlation_id varchar(128) not null,
    unique(idempotency_key)
);

create index idx_agent_command_agent_status on agent_command(agent_id, status, available_at, id);
create index idx_agent_command_status_lease on agent_command(status, lease_expires_at, id);

create table worker_artifact (
    id varchar(64) primary key,
    worker_type varchar(64) not null,
    owner_type varchar(64) not null,
    owner_id varchar(128) not null,
    artifact_type varchar(64) not null,
    object_uri varchar(2048) not null,
    content_hash varchar(128) not null,
    bytes_count bigint not null,
    metadata_json text not null,
    created_at timestamp not null
);

create index idx_worker_artifact_owner on worker_artifact(owner_type, owner_id, created_at);
