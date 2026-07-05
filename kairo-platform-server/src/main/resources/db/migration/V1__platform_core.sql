create table enterprise (
    id varchar(64) primary key,
    name varchar(255) not null,
    created_at timestamp not null default current_timestamp
);

create table organization (
    id varchar(64) primary key,
    enterprise_id varchar(64) not null references enterprise(id),
    name varchar(255) not null,
    created_at timestamp not null default current_timestamp
);

create table project (
    id varchar(64) primary key,
    organization_id varchar(64) not null references organization(id),
    name varchar(255) not null,
    created_at timestamp not null default current_timestamp
);

create table application (
    id varchar(64) primary key,
    project_id varchar(64) not null references project(id),
    name varchar(255) not null,
    created_at timestamp not null default current_timestamp
);

create table environment (
    id varchar(64) primary key,
    application_id varchar(64) not null references application(id),
    name varchar(128) not null,
    type varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    unique(application_id, name)
);

create table user_account (
    id varchar(64) primary key,
    username varchar(255) not null unique,
    display_name varchar(255) not null,
    status varchar(64) not null,
    created_at timestamp not null default current_timestamp
);

create table external_identity (
    id varchar(64) primary key,
    user_id varchar(64) not null references user_account(id),
    provider varchar(128) not null,
    subject varchar(512) not null,
    created_at timestamp not null default current_timestamp,
    unique(provider, subject)
);

create table role (
    id varchar(64) primary key,
    name varchar(128) not null unique,
    description varchar(1024) not null default '',
    created_at timestamp not null default current_timestamp
);

create table permission (
    id varchar(64) primary key,
    capability varchar(128) not null unique,
    description varchar(1024) not null default ''
);

create table role_permission (
    role_id varchar(64) not null references role(id),
    permission_id varchar(64) not null references permission(id),
    primary key(role_id, permission_id)
);

create table resource_scope (
    id varchar(64) primary key,
    resource_type varchar(64) not null,
    resource_id varchar(128) not null,
    created_at timestamp not null default current_timestamp,
    unique(resource_type, resource_id)
);

create table user_role_binding (
    id varchar(64) primary key,
    user_id varchar(64) not null references user_account(id),
    role_id varchar(64) not null references role(id),
    scope_id varchar(64) references resource_scope(id),
    created_at timestamp not null default current_timestamp,
    expires_at timestamp,
    unique(user_id, role_id, scope_id)
);

create table recording_session (
    id varchar(64) primary key,
    application_id varchar(64) not null,
    environment_id varchar(64) not null,
    status varchar(64) not null,
    version bigint not null,
    max_events bigint not null,
    ttl_seconds bigint not null,
    target_json text not null,
    quota_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_by varchar(255) not null,
    updated_at timestamp not null
);

create index idx_recording_session_app_env_status on recording_session(application_id, environment_id, status);

create table dataset (
    id varchar(64) primary key,
    name varchar(255) not null,
    application_id varchar(64) not null,
    environment_id varchar(64) not null,
    created_by varchar(255) not null,
    created_at timestamp not null
);

create table dataset_version (
    id varchar(128) primary key,
    dataset_id varchar(64) not null references dataset(id),
    version bigint not null,
    source_session_id varchar(64) not null references recording_session(id),
    schema_hash varchar(128) not null,
    manifest_hash varchar(128) not null,
    masking_hash varchar(128) not null,
    retention_policy varchar(64) not null,
    object_references_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    unique(dataset_id, version)
);

create index idx_dataset_version_dataset on dataset_version(dataset_id, version);

create table replay_plan (
    id varchar(64) primary key,
    version bigint not null,
    dataset_id varchar(64) not null,
    dataset_version bigint not null,
    target_environment varchar(128) not null,
    target_application varchar(128) not null,
    status varchar(64) not null,
    side_effect_policy_hash varchar(128) not null,
    comparison_policy_hash varchar(128) not null,
    execution_policy_json text not null,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_by varchar(255) not null,
    updated_at timestamp not null,
    foreign key(dataset_id, dataset_version) references dataset_version(dataset_id, version)
);

create index idx_replay_plan_status on replay_plan(target_application, target_environment, status);

create table approval_request (
    id varchar(64) primary key,
    subject_type varchar(64) not null,
    subject_id varchar(128) not null,
    subject_version bigint not null,
    subject_hash varchar(128) not null,
    status varchar(64) not null,
    requester varchar(255) not null,
    reason varchar(2048) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table approval_step (
    id varchar(64) primary key,
    approval_id varchar(64) not null references approval_request(id),
    step_order integer not null,
    approver varchar(255) not null,
    status varchar(64) not null,
    decided_at timestamp,
    unique(approval_id, step_order)
);

create table approval_decision (
    id varchar(64) primary key,
    approval_id varchar(64) not null references approval_request(id),
    step_id varchar(64) references approval_step(id),
    actor varchar(255) not null,
    decision varchar(64) not null,
    reason varchar(2048) not null,
    decided_at timestamp not null
);

create table audit_record (
    sequence bigint generated always as identity primary key,
    id varchar(64) not null unique,
    occurred_at timestamp not null,
    actor varchar(255) not null,
    identity_source varchar(128) not null,
    action varchar(128) not null,
    resource_type varchar(64) not null,
    resource_id varchar(128) not null,
    resource_version bigint not null,
    before_hash varchar(128) not null,
    after_hash varchar(128) not null,
    previous_record_hash varchar(128) not null,
    record_hash varchar(128) not null,
    correlation_id varchar(128) not null,
    ip_address varchar(128) not null,
    result varchar(64) not null,
    reason varchar(2048) not null,
    details_json text not null
);

create index idx_audit_resource on audit_record(resource_type, resource_id, occurred_at);
create index idx_audit_occurred_at on audit_record(occurred_at);

create table outbox_event (
    id varchar(64) primary key,
    aggregate_type varchar(64) not null,
    aggregate_id varchar(128) not null,
    event_type varchar(128) not null,
    payload_json text not null,
    status varchar(64) not null,
    available_at timestamp not null,
    created_at timestamp not null,
    published_at timestamp,
    attempts integer not null default 0,
    last_error text
);

create index idx_outbox_status_available on outbox_event(status, available_at);

insert into enterprise(id, name) values ('ent-default', 'Default Enterprise');
insert into organization(id, enterprise_id, name) values ('org-default', 'ent-default', 'Default Organization');
insert into project(id, organization_id, name) values ('proj-default', 'org-default', 'Default Project');
insert into application(id, project_id, name) values ('app-default', 'proj-default', 'Default Application');
insert into environment(id, application_id, name, type) values ('env-dev', 'app-default', 'dev', 'DEVELOPMENT');

insert into user_account(id, username, display_name, status) values
    ('user-system', 'system', 'System Administrator', 'ACTIVE'),
    ('user-reviewer', 'reviewer', 'Default Reviewer', 'ACTIVE');

insert into role(id, name, description) values
    ('role-admin', 'PlatformAdmin', 'Full platform administrator'),
    ('role-operator', 'Operator', 'Kairo operator'),
    ('role-auditor', 'Auditor', 'Audit reader');

insert into permission(id, capability, description) values
    ('perm-observe', 'OBSERVE', 'Read Kairo state'),
    ('perm-record', 'RECORD_ARGUMENTS', 'Record arguments'),
    ('perm-record-return', 'RECORD_RETURN', 'Record return value'),
    ('perm-replay', 'IMPORT_TO_TEST', 'Import dataset and replay to test'),
    ('perm-export', 'EXPORT_DATA', 'Export data'),
    ('perm-reset', 'RESET', 'Reset or shutdown Kairo components'),
    ('perm-approval', 'APPROVE', 'Approve high risk operations'),
    ('perm-admin', 'ADMIN', 'Administer platform');

insert into role_permission(role_id, permission_id)
select 'role-admin', id from permission;

insert into role_permission(role_id, permission_id) values
    ('role-operator', 'perm-observe'),
    ('role-operator', 'perm-record'),
    ('role-operator', 'perm-record-return'),
    ('role-operator', 'perm-replay'),
    ('role-auditor', 'perm-observe');

insert into resource_scope(id, resource_type, resource_id) values ('scope-global', 'GLOBAL', '*');
insert into user_role_binding(id, user_id, role_id, scope_id)
values ('binding-system-admin', 'user-system', 'role-admin', 'scope-global');
insert into user_role_binding(id, user_id, role_id, scope_id)
values ('binding-reviewer-admin', 'user-reviewer', 'role-admin', 'scope-global');
