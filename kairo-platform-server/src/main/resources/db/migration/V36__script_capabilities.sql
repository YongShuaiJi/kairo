-- V1.2 phase 4: platform persistence for script capability policy and temporary script sessions.
--
-- §3.5 introduces three tables:
--   * script_capability_policy : platform / application allowed-max tier, revision and modifier;
--   * script_session           : one temporary trial session (target, tier, TTL, status, agent result);
--   * script_session_event     : per-session state-change history (complements the unified audit log).
--
-- All mutable state uses optimistic locking (revision / version) and idempotency keys; binary script
-- sources are never persisted (only the script hash), matching the bytecode-metadata precedent.

create table script_capability_policy (
    scope varchar(16) not null,
    application_id varchar(64) not null,
    allowed_max_profile varchar(16) not null,
    revision bigint not null,
    policy_hash varchar(128) not null,
    modified_by varchar(255) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint pk_script_capability_policy primary key (scope, application_id),
    constraint ck_script_policy_scope check (scope in ('PLATFORM', 'APPLICATION')),
    constraint ck_script_policy_profile
        check (allowed_max_profile in ('SAFE', 'EXTENDED', 'UNRESTRICTED')),
    constraint ck_script_policy_app_scope check (
        (scope = 'PLATFORM' and application_id = '__platform__')
        or (scope = 'APPLICATION' and application_id <> '__platform__')
    ),
    constraint ck_script_policy_revision check (revision >= 0)
);

-- The platform-level ceiling is seeded once; operators raise or lower it through the policy service.
-- Defaulting to UNRESTRICTED means the platform imposes no ceiling until explicitly tightened; the
-- effective tier is still min(platform, application, requested), so applications and callers bound it.
insert into script_capability_policy(scope, application_id, allowed_max_profile, revision,
        policy_hash, modified_by, created_at, updated_at)
values ('PLATFORM', '__platform__', 'UNRESTRICTED', 0,
        'platform:UNRESTRICTED:0', 'system', current_timestamp, current_timestamp);

create table script_session (
    id varchar(64) primary key,
    agent_id varchar(64) not null,
    application_id varchar(64) not null,
    target_class_name varchar(1024) not null,
    target_class_loader_id varchar(512),
    target_method_name varchar(255) not null,
    target_method_descriptor varchar(1024) not null,
    script_hash varchar(128) not null,
    requested_profile varchar(16) not null,
    effective_profile varchar(16) not null,
    platform_max_profile varchar(16) not null,
    application_max_profile varchar(16) not null,
    policy_revision bigint not null,
    policy_hash varchar(128) not null,
    ttl_millis bigint not null,
    max_hits bigint not null,
    status varchar(16) not null,
    hit_count bigint not null default 0,
    version bigint not null,
    idempotency_key varchar(255) not null,
    requested_by varchar(255) not null,
    formal_rule_id varchar(64),
    agent_result_json text not null default '{}',
    diagnostics_json text not null default '[]',
    created_at timestamp not null,
    expires_at timestamp not null,
    applied_at timestamp,
    reverted_at timestamp,
    updated_at timestamp not null,
    created_by varchar(255) not null,
    correlation_id varchar(128) not null,
    constraint ck_script_session_profile
        check (requested_profile in ('SAFE', 'EXTENDED', 'UNRESTRICTED')
           and effective_profile in ('SAFE', 'EXTENDED', 'UNRESTRICTED')
           and platform_max_profile in ('SAFE', 'EXTENDED', 'UNRESTRICTED')
           and application_max_profile in ('SAFE', 'EXTENDED', 'UNRESTRICTED')),
    constraint ck_script_session_status
        check (status in ('CREATED', 'VALIDATED', 'APPLIED', 'EXPIRED', 'REVERTED', 'FAILED')),
    constraint ck_script_session_ttl check (ttl_millis > 0),
    constraint ck_script_session_max_hits check (max_hits > 0),
    constraint ck_script_session_hit_count check (hit_count >= 0),
    constraint ck_script_session_version check (version >= 1),
    constraint ck_script_session_revision check (policy_revision >= 0),
    constraint uq_script_session_idempotency unique (idempotency_key)
);

create index idx_script_session_agent_status on script_session(agent_id, status, updated_at);
create index idx_script_session_app on script_session(application_id, created_at);
create index idx_script_session_expiry on script_session(status, expires_at);
create index idx_script_session_target
    on script_session(target_class_name, target_class_loader_id, target_method_name, status);

create table script_session_event (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    action varchar(64) not null,
    from_status varchar(16),
    to_status varchar(16) not null,
    actor varchar(255) not null,
    detail varchar(1024),
    command_id varchar(64),
    created_at timestamp not null,
    constraint ck_script_session_event_status
        check (to_status in ('CREATED', 'VALIDATED', 'APPLIED', 'EXPIRED', 'REVERTED', 'FAILED')
           and (from_status is null or from_status in
                ('CREATED', 'VALIDATED', 'APPLIED', 'EXPIRED', 'REVERTED', 'FAILED')))
);

create index idx_script_session_event_session on script_session_event(session_id, created_at);
