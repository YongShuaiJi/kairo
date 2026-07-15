-- V1.6 API-First / AI-First: unified Operation, AutomationSession, API-token scope
-- (docs/roadmap/v1.x-technical/v1.6-api-ai-first.md §4, §5.1)

-- Unified long-running Operation resource (§2.2 operationId / §5.1).
-- Converges agent command, publish, rollback, unload, preview, script-session,
-- reconcile and automation trial/promote/revert into one queryable resource.
create table operation (
    id varchar(64) primary key,
    operation_type varchar(64) not null,
    status varchar(64) not null,
    resource_type varchar(64) not null default '',
    resource_id varchar(128) not null default '',
    risk_level varchar(32) not null default 'LOW',
    impact_json text not null default '{}',
    progress integer not null default -1,
    result_json text not null default '{}',
    error_json text,
    revert_operation_id varchar(64),
    automation_session_id varchar(64),
    agent_command_id varchar(64),
    correlation_id varchar(128) not null default '',
    actor varchar(255) not null default '',
    idempotency_key varchar(255),
    version bigint not null default 0,
    created_at timestamp not null,
    updated_at timestamp not null,
    completed_at timestamp,
    constraint ck_operation_status check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED','REVERTED','TIMEOUT')),
    constraint ck_operation_type check (operation_type in ('AGENT_COMMAND','RULE_PUBLISH','RULE_ROLLBACK','RULE_UNLOAD','PREVIEW','SCRIPT_SESSION','RECONCILE','AUTOMATION_TRIAL','AUTOMATION_PROMOTE','AUTOMATION_REVERT')),
    constraint ck_operation_risk check (risk_level in ('LOW','MEDIUM','HIGH','CRITICAL'))
);

create index idx_operation_resource on operation(resource_type, resource_id, created_at desc);
create index idx_operation_status on operation(status, updated_at);
create index idx_operation_session on operation(automation_session_id);
create index idx_operation_idempotency on operation(idempotency_key);

-- Append-only Operation event stream (§5.1 operation_event).
create table operation_event (
    id varchar(64) primary key,
    operation_id varchar(64) not null,
    sequence bigint not null,
    event_type varchar(64) not null,
    actor varchar(255) not null default '',
    detail_json text not null default '{}',
    occurred_at timestamp not null,
    unique(operation_id, sequence)
);

create index idx_operation_event_op on operation_event(operation_id, sequence);

-- AutomationSession: the AI/automation top-level boundary (§4.1).
-- A session is NOT a permission principal; it can only narrow the token's scope.
create table automation_session (
    id varchar(64) primary key,
    caller varchar(255) not null,
    source varchar(64) not null,
    application_id varchar(64) not null,
    environment_id varchar(64),
    instance_id varchar(64),
    agent_id varchar(64),
    max_capability_profile varchar(32) not null,
    ttl_millis bigint not null,
    deadline_millis bigint not null,
    status varchar(64) not null,
    risk_level varchar(32) not null default 'LOW',
    cleanup_result_json text not null default '{}',
    correlation_id varchar(128) not null default '',
    token_id varchar(64),
    version bigint not null default 0,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint ck_auto_session_status check (status in ('CREATED','ACTIVE','COMPLETED','EXPIRED','REVERTED','FAILED')),
    constraint ck_auto_session_profile check (max_capability_profile in ('SAFE','EXTENDED','UNRESTRICTED')),
    constraint ck_auto_session_risk check (risk_level in ('LOW','MEDIUM','HIGH','CRITICAL'))
);

create index idx_auto_session_status_deadline on automation_session(status, deadline_millis);
create index idx_auto_session_token on automation_session(token_id);

-- Resources created within a session, tracked for one-click revert (§4.1 / §5.1).
create table automation_session_resource (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    resource_type varchar(64) not null,
    resource_id varchar(128) not null,
    reversible boolean not null default true,
    created_at timestamp not null,
    unique(session_id, resource_type, resource_id)
);

create index idx_auto_session_resource_session on automation_session_resource(session_id);

-- Extend API Token with scope, source and optional session limit (§5.1).
-- scope_json: JSON array of {resourceType, resourceId, capabilities} granting
-- the token a narrow capability set; null means "inherit subject's full capabilities".
alter table platform_access_token add column scope_json text;
alter table platform_access_token add column source varchar(64);
alter table platform_access_token add column max_sessions integer;
