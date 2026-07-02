create table attach_executor (
    id varchar(64) primary key,
    executor_type varchar(64) not null,
    hostname varchar(255) not null,
    endpoint varchar(512) not null,
    status varchar(64) not null,
    executor_version varchar(128) not null,
    capabilities_json text not null,
    last_heartbeat_at timestamp,
    lease_expires_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_attach_executor_status
    on attach_executor(status, last_heartbeat_at);

create table attach_executor_target (
    executor_id varchar(64) not null references attach_executor(id),
    instance_id varchar(64) not null references instance(id),
    process_id varchar(128) not null,
    agent_jar varchar(512) not null,
    runtime varchar(128) not null,
    java_version varchar(128) not null,
    status varchar(64) not null,
    capabilities_json text not null,
    last_seen_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (executor_id, instance_id)
);

create index idx_attach_executor_target_instance
    on attach_executor_target(instance_id, status, updated_at);

alter table sidecar_instance
    add column executor_id varchar(64) references attach_executor(id);

