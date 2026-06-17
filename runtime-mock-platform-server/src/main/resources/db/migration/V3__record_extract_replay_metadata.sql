create table recording_rule (
    id varchar(64) primary key,
    application_id varchar(64) not null references application(id),
    environment_id varchar(64) not null references environment(id),
    name varchar(255) not null,
    status varchar(64) not null,
    current_draft_version bigint,
    latest_version bigint,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_by varchar(255) not null,
    updated_at timestamp not null
);

create index idx_recording_rule_app_env_status on recording_rule(application_id, environment_id, status);

create table masking_policy (
    id varchar(64) primary key,
    application_id varchar(64) not null references application(id),
    environment_id varchar(64) not null references environment(id),
    name varchar(255) not null,
    policy_hash varchar(128) not null,
    policy_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null
);

create table tokenization_policy (
    id varchar(64) primary key,
    application_id varchar(64) not null references application(id),
    environment_id varchar(64) not null references environment(id),
    name varchar(255) not null,
    domain varchar(255) not null,
    policy_hash varchar(128) not null,
    policy_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null
);

create table recording_rule_version (
    id varchar(128) primary key,
    recording_rule_id varchar(64) not null references recording_rule(id),
    version bigint not null,
    status varchar(64) not null,
    protocol varchar(64) not null,
    target_json text not null,
    sampling_json text not null,
    quota_json text not null,
    masking_policy_id varchar(64) references masking_policy(id),
    created_by varchar(255) not null,
    created_at timestamp not null,
    unique(recording_rule_id, version)
);

create table recording_session_target (
    id varchar(64) primary key,
    recording_session_id varchar(64) not null references recording_session(id),
    protocol varchar(64) not null,
    target_json text not null,
    created_at timestamp not null
);

create table recording_session_quota (
    id varchar(64) primary key,
    recording_session_id varchar(64) not null references recording_session(id),
    max_events bigint not null,
    max_bytes bigint not null,
    expires_at timestamp not null,
    created_at timestamp not null
);

create table recording_batch (
    id varchar(64) primary key,
    recording_session_id varchar(64) not null references recording_session(id),
    status varchar(64) not null,
    object_uri varchar(2048) not null,
    event_count bigint not null,
    bytes_count bigint not null,
    created_at timestamp not null,
    sealed_at timestamp
);

create table payload_object (
    id varchar(64) primary key,
    content_hash varchar(128) not null,
    encryption_domain varchar(255) not null,
    object_uri varchar(2048) not null,
    bytes_count bigint not null,
    created_at timestamp not null,
    unique(content_hash, encryption_domain)
);

create table payload_reference (
    id varchar(64) primary key,
    payload_object_id varchar(64) not null references payload_object(id),
    logical_path varchar(1024) not null,
    reference_json text not null,
    created_at timestamp not null
);

create table recording_event_index (
    id varchar(64) primary key,
    recording_session_id varchar(64) not null references recording_session(id),
    recording_batch_id varchar(64) references recording_batch(id),
    trace_id varchar(128) not null,
    span_id varchar(128) not null,
    protocol varchar(64) not null,
    event_time timestamp not null,
    payload_reference_id varchar(64) references payload_reference(id),
    metadata_json text not null
);

create index idx_recording_event_session_time on recording_event_index(recording_session_id, event_time, id);

create table dataset_source_session (
    id varchar(64) primary key,
    dataset_version_id varchar(128) not null references dataset_version(id),
    recording_session_id varchar(64) not null references recording_session(id),
    created_at timestamp not null,
    unique(dataset_version_id, recording_session_id)
);

create table dataset_schema (
    id varchar(64) primary key,
    dataset_version_id varchar(128) not null references dataset_version(id),
    schema_hash varchar(128) not null,
    schema_json text not null,
    created_at timestamp not null
);

create table dataset_manifest (
    id varchar(64) primary key,
    dataset_version_id varchar(128) not null references dataset_version(id),
    manifest_hash varchar(128) not null,
    manifest_json text not null,
    created_at timestamp not null
);

create table dataset_object_reference (
    id varchar(64) primary key,
    dataset_version_id varchar(128) not null references dataset_version(id),
    object_type varchar(64) not null,
    object_uri varchar(2048) not null,
    content_hash varchar(128) not null,
    bytes_count bigint not null,
    created_at timestamp not null
);

create table identity_mapping (
    id varchar(64) primary key,
    dataset_version_id varchar(128) not null references dataset_version(id),
    mapping_domain varchar(255) not null,
    source_hash varchar(128) not null,
    target_value varchar(512) not null,
    created_at timestamp not null,
    unique(dataset_version_id, mapping_domain, source_hash)
);

create table cleanup_manifest (
    id varchar(64) primary key,
    dataset_version_id varchar(128) not null references dataset_version(id),
    manifest_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null
);

create table legal_hold (
    id varchar(64) primary key,
    dataset_version_id varchar(128) not null references dataset_version(id),
    status varchar(64) not null,
    reason varchar(2048) not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    released_at timestamp
);

create table data_retention_policy (
    id varchar(64) primary key,
    dataset_id varchar(64) not null references dataset(id),
    retention_policy varchar(64) not null,
    deletion_policy_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null
);

create table data_deletion_execution (
    id varchar(64) primary key,
    dataset_version_id varchar(128) not null references dataset_version(id),
    status varchar(64) not null,
    reason varchar(2048) not null,
    proof_hash varchar(128) not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    finished_at timestamp
);

create table datasource_registration (
    id varchar(64) primary key,
    application_id varchar(64) not null references application(id),
    environment_id varchar(64) not null references environment(id),
    datasource_type varchar(64) not null,
    name varchar(255) not null,
    status varchar(64) not null,
    config_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table datasource_credential_ref (
    id varchar(64) primary key,
    datasource_id varchar(64) not null references datasource_registration(id),
    provider varchar(128) not null,
    secret_ref varchar(512) not null,
    created_by varchar(255) not null,
    created_at timestamp not null
);

create table extraction_template (
    id varchar(64) primary key,
    datasource_id varchar(64) not null references datasource_registration(id),
    name varchar(255) not null,
    status varchar(64) not null,
    current_draft_version bigint,
    latest_version bigint,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_by varchar(255) not null,
    updated_at timestamp not null
);

create table extraction_template_version (
    id varchar(128) primary key,
    template_id varchar(64) not null references extraction_template(id),
    version bigint not null,
    status varchar(64) not null,
    root_table varchar(255) not null,
    template_hash varchar(128) not null,
    template_json text not null,
    quota_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    unique(template_id, version)
);

create table extraction_relation (
    id varchar(64) primary key,
    template_version_id varchar(128) not null references extraction_template_version(id),
    source_table varchar(255) not null,
    target_table varchar(255) not null,
    relation_json text not null,
    created_at timestamp not null
);

create table extraction_task (
    id varchar(64) primary key,
    template_id varchar(64) not null references extraction_template(id),
    template_version bigint not null,
    dataset_id varchar(64),
    status varchar(64) not null,
    version bigint not null,
    parameters_json text not null,
    quota_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_by varchar(255) not null,
    updated_at timestamp not null,
    unique(template_id, template_version, id)
);

create index idx_extraction_task_status on extraction_task(status, updated_at);

create table extraction_execution (
    id varchar(64) primary key,
    extraction_task_id varchar(64) not null references extraction_task(id),
    worker_id varchar(128) not null,
    status varchar(64) not null,
    started_at timestamp,
    finished_at timestamp,
    metrics_json text not null,
    error_message text
);

create table extraction_result (
    id varchar(64) primary key,
    extraction_task_id varchar(64) not null references extraction_task(id),
    result_type varchar(64) not null,
    object_uri varchar(2048) not null,
    row_count bigint not null,
    bytes_count bigint not null,
    content_hash varchar(128) not null,
    created_at timestamp not null
);

create table extraction_quota (
    id varchar(64) primary key,
    extraction_task_id varchar(64) not null references extraction_task(id),
    max_rows bigint not null,
    max_bytes bigint not null,
    timeout_seconds bigint not null,
    created_at timestamp not null
);

create table replay_plan_version (
    id varchar(128) primary key,
    replay_plan_id varchar(64) not null references replay_plan(id),
    version bigint not null,
    status varchar(64) not null,
    plan_hash varchar(128) not null,
    plan_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    unique(replay_plan_id, version)
);

create table replay_target (
    id varchar(64) primary key,
    replay_plan_id varchar(64) not null references replay_plan(id),
    target_type varchar(64) not null,
    target_json text not null,
    created_at timestamp not null
);

create table replay_side_effect_policy (
    id varchar(64) primary key,
    replay_plan_id varchar(64) not null references replay_plan(id),
    policy_hash varchar(128) not null,
    policy_json text not null,
    created_at timestamp not null
);

create table comparison_policy (
    id varchar(64) primary key,
    replay_plan_id varchar(64) not null references replay_plan(id),
    policy_hash varchar(128) not null,
    policy_json text not null,
    created_at timestamp not null
);

create table replay_execution (
    id varchar(64) primary key,
    replay_plan_id varchar(64) not null references replay_plan(id),
    status varchar(64) not null,
    version bigint not null,
    executor_config_json text not null,
    metrics_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_by varchar(255) not null,
    updated_at timestamp not null
);

create index idx_replay_execution_status on replay_execution(status, updated_at);

create table replay_batch (
    id varchar(64) primary key,
    replay_execution_id varchar(64) not null references replay_execution(id),
    batch_order integer not null,
    status varchar(64) not null,
    trace_selector_json text not null,
    started_at timestamp,
    finished_at timestamp,
    unique(replay_execution_id, batch_order)
);

create table replay_invocation_result (
    id varchar(64) primary key,
    replay_batch_id varchar(64) not null references replay_batch(id),
    invocation_key varchar(512) not null,
    status varchar(64) not null,
    request_hash varchar(128) not null,
    response_hash varchar(128) not null,
    error_message text,
    duration_millis bigint not null,
    created_at timestamp not null
);

create table comparison_result (
    id varchar(64) primary key,
    replay_invocation_result_id varchar(64) not null references replay_invocation_result(id),
    status varchar(64) not null,
    diff_json text not null,
    created_at timestamp not null
);

create table replay_cleanup_execution (
    id varchar(64) primary key,
    replay_execution_id varchar(64) not null references replay_execution(id),
    status varchar(64) not null,
    cleanup_manifest_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    finished_at timestamp
);

insert into permission(id, capability, description) values
    ('perm-data-extract', 'DATA_EXTRACT', 'Create extraction templates and tasks'),
    ('perm-replay-execute', 'REPLAY_EXECUTE', 'Execute replay plans');

insert into role_permission(role_id, permission_id)
select 'role-admin', id from permission where capability in ('DATA_EXTRACT', 'REPLAY_EXECUTE');

insert into role_permission(role_id, permission_id)
select 'role-operator', id from permission where capability in ('DATA_EXTRACT', 'REPLAY_EXECUTE');
