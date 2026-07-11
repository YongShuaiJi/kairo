create table bytecode_transformation_metadata (
    runtime_instance_id varchar(64) not null,
    agent_id varchar(64) not null,
    binary_class_name varchar(1024) not null,
    class_loader_id varchar(512) not null,
    revision bigint not null,
    snapshot_kind varchar(32) not null,
    bytecode_hash varchar(128),
    size_bytes bigint,
    transformation_status varchar(64) not null,
    diagnostics_json text not null default '[]',
    observed_at timestamp not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint pk_bytecode_transformation_metadata primary key
        (runtime_instance_id, agent_id, binary_class_name, class_loader_id, revision, snapshot_kind),
    constraint ck_bytecode_metadata_revision check (revision >= 0),
    constraint ck_bytecode_metadata_size check (size_bytes is null or size_bytes >= 0)
);

create index idx_bytecode_metadata_class_history
    on bytecode_transformation_metadata
       (runtime_instance_id, binary_class_name, class_loader_id, revision desc, observed_at desc);

create index idx_bytecode_metadata_agent_time
    on bytecode_transformation_metadata(agent_id, observed_at desc);

