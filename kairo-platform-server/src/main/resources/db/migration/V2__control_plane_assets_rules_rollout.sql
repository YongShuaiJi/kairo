create table instance (
    id varchar(64) primary key,
    application_id varchar(64) not null references application(id),
    environment_id varchar(64) not null references environment(id),
    hostname varchar(255) not null,
    process_id varchar(64) not null,
    runtime varchar(128) not null,
    status varchar(64) not null,
    labels_json text not null,
    last_seen_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_instance_app_env_status on instance(application_id, environment_id, status);

create table instance_label (
    id varchar(64) primary key,
    instance_id varchar(64) not null references instance(id),
    label_key varchar(128) not null,
    label_value varchar(512) not null,
    created_at timestamp not null,
    unique(instance_id, label_key)
);

create table asset_claim (
    id varchar(64) primary key,
    instance_id varchar(64) references instance(id),
    claim_type varchar(64) not null,
    claim_value varchar(512) not null,
    status varchar(64) not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    expires_at timestamp
);

create table sidecar_instance (
    id varchar(64) primary key,
    instance_id varchar(64) references instance(id),
    status varchar(64) not null,
    sidecar_version varchar(128) not null,
    endpoint varchar(512) not null,
    capabilities_json text not null,
    last_heartbeat_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_sidecar_status on sidecar_instance(status, last_heartbeat_at);

create table agent_instance (
    id varchar(64) primary key,
    instance_id varchar(64) references instance(id),
    sidecar_id varchar(64) references sidecar_instance(id),
    status varchar(64) not null,
    agent_version varchar(128) not null,
    bootstrap_version varchar(128) not null,
    listen_host varchar(255) not null,
    listen_port integer not null,
    token_hash varchar(128) not null,
    capabilities_json text not null,
    last_heartbeat_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index idx_agent_status on agent_instance(status, last_heartbeat_at);

create table agent_registration (
    id varchar(64) primary key,
    agent_id varchar(64) not null references agent_instance(id),
    registration_token_hash varchar(128) not null,
    expires_at timestamp not null,
    consumed_at timestamp,
    created_at timestamp not null
);

create table agent_capability (
    id varchar(64) primary key,
    agent_id varchar(64) not null references agent_instance(id),
    capability varchar(128) not null,
    metadata_json text not null,
    created_at timestamp not null,
    unique(agent_id, capability)
);

create table agent_heartbeat (
    id varchar(64) primary key,
    agent_id varchar(64) not null references agent_instance(id),
    status varchar(64) not null,
    metrics_json text not null,
    received_at timestamp not null
);

create table degraded_class (
    id varchar(64) primary key,
    agent_id varchar(64) not null references agent_instance(id),
    class_name varchar(512) not null,
    reason varchar(2048) not null,
    first_seen_at timestamp not null,
    last_seen_at timestamp not null,
    unique(agent_id, class_name)
);

create table component_version (
    id varchar(64) primary key,
    component_type varchar(64) not null,
    version varchar(128) not null,
    status varchar(64) not null,
    checksum varchar(128) not null,
    metadata_json text not null,
    created_at timestamp not null,
    unique(component_type, version)
);

create table protocol_compatibility (
    id varchar(64) primary key,
    component_type varchar(64) not null,
    min_version varchar(128) not null,
    max_version varchar(128) not null,
    protocol_version varchar(64) not null,
    status varchar(64) not null,
    created_at timestamp not null
);

create table rule (
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

create index idx_rule_app_env_status on rule(application_id, environment_id, status);

create table rule_version (
    id varchar(128) primary key,
    rule_id varchar(64) not null references rule(id),
    version bigint not null,
    status varchar(64) not null,
    risk_level varchar(64) not null,
    matcher_json text not null,
    script_hash varchar(128) not null,
    script_json text not null,
    governance_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    unique(rule_id, version)
);

create table rule_target (
    id varchar(64) primary key,
    rule_version_id varchar(128) not null references rule_version(id),
    protocol varchar(64) not null,
    class_name varchar(512) not null,
    method_name varchar(255) not null,
    matcher_json text not null,
    created_at timestamp not null
);

create index idx_rule_target_method on rule_target(class_name, method_name);

create table rule_capability (
    id varchar(64) primary key,
    rule_version_id varchar(128) not null references rule_version(id),
    capability varchar(128) not null,
    created_at timestamp not null,
    unique(rule_version_id, capability)
);

create table rule_lock (
    id varchar(64) primary key,
    rule_id varchar(64) not null references rule(id),
    owner varchar(255) not null,
    fencing_token varchar(128) not null,
    expires_at timestamp not null,
    created_at timestamp not null
);

create table rule_runtime_status (
    id varchar(64) primary key,
    rule_id varchar(64) not null references rule(id),
    rule_version bigint not null,
    instance_id varchar(64) not null references instance(id),
    status varchar(64) not null,
    hit_count bigint not null default 0,
    error_count bigint not null default 0,
    last_error text,
    updated_at timestamp not null,
    unique(rule_id, rule_version, instance_id)
);

create table rule_instance_binding (
    id varchar(64) primary key,
    rule_id varchar(64) not null references rule(id),
    rule_version bigint not null,
    instance_id varchar(64) not null references instance(id),
    binding_status varchar(64) not null,
    applied_at timestamp,
    removed_at timestamp,
    unique(rule_id, rule_version, instance_id)
);

create table operation_plan (
    id varchar(64) primary key,
    application_id varchar(64) not null references application(id),
    environment_id varchar(64) not null references environment(id),
    plan_type varchar(64) not null,
    resource_type varchar(64) not null,
    resource_id varchar(128) not null,
    resource_version bigint not null,
    status varchar(64) not null,
    version bigint not null,
    strategy_json text not null,
    approval_id varchar(64) references approval_request(id),
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_by varchar(255) not null,
    updated_at timestamp not null
);

create index idx_operation_plan_status on operation_plan(application_id, environment_id, status);

create table rollout_plan (
    id varchar(64) primary key,
    operation_plan_id varchar(64) not null references operation_plan(id),
    mode varchar(64) not null,
    batch_policy_json text not null,
    rollback_policy_json text not null,
    created_at timestamp not null
);

create table rollout_batch (
    id varchar(64) primary key,
    operation_plan_id varchar(64) not null references operation_plan(id),
    batch_order integer not null,
    status varchar(64) not null,
    target_selector_json text not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique(operation_plan_id, batch_order)
);

create table rollout_target_snapshot (
    id varchar(64) primary key,
    operation_plan_id varchar(64) not null references operation_plan(id),
    instance_id varchar(64) not null references instance(id),
    labels_json text not null,
    agent_status varchar(64) not null,
    captured_at timestamp not null,
    unique(operation_plan_id, instance_id)
);

create table rollout_instance_execution (
    id varchar(64) primary key,
    rollout_batch_id varchar(64) not null references rollout_batch(id),
    instance_id varchar(64) not null references instance(id),
    status varchar(64) not null,
    expected_agent_version varchar(128) not null,
    expected_rule_version bigint,
    command_id varchar(128),
    error_message text,
    started_at timestamp,
    finished_at timestamp,
    updated_at timestamp not null,
    unique(rollout_batch_id, instance_id)
);

create index idx_rollout_execution_instance_status on rollout_instance_execution(instance_id, status);

create table rollback_execution (
    id varchar(64) primary key,
    operation_plan_id varchar(64) not null references operation_plan(id),
    rollback_type varchar(64) not null,
    status varchar(64) not null,
    reason varchar(2048) not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    finished_at timestamp
);

create table observation_window (
    id varchar(64) primary key,
    operation_plan_id varchar(64) not null references operation_plan(id),
    status varchar(64) not null,
    started_at timestamp not null,
    ends_at timestamp not null,
    success_criteria_json text not null,
    failure_criteria_json text not null,
    result_json text not null
);

create table external_health_signal (
    id varchar(64) primary key,
    operation_plan_id varchar(64) references operation_plan(id),
    source varchar(128) not null,
    severity varchar(64) not null,
    signal_json text not null,
    received_at timestamp not null
);

insert into permission(id, capability, description) values
    ('perm-instance-manage', 'INSTANCE_MANAGE', 'Manage application instances'),
    ('perm-agent-manage', 'AGENT_MANAGE', 'Manage sidecars and agents'),
    ('perm-rule-manage', 'RULE_MANAGE', 'Create and manage rules'),
    ('perm-rollout-manage', 'ROLLOUT_MANAGE', 'Create and manage rollout plans');

insert into role_permission(role_id, permission_id)
select 'role-admin', id from permission where capability in ('INSTANCE_MANAGE', 'AGENT_MANAGE', 'RULE_MANAGE', 'ROLLOUT_MANAGE');

insert into role_permission(role_id, permission_id)
select 'role-operator', id from permission where capability in ('INSTANCE_MANAGE', 'RULE_MANAGE', 'ROLLOUT_MANAGE');
