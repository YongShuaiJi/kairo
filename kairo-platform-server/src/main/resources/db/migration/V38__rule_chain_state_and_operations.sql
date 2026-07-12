-- V1.4 §4.3: rule-chain desired/actual state, fenced operations and precise unload.
--
-- The Platform now persists, per (application, environment, agent, enhancement target), the
-- desired RuleChainSpec (monotonic revision + canonical hash + ordered rule entries) and the
-- actual state each Agent reports back (applied revision/hash, transformation revision/hash,
-- degraded reason). Chain operations (APPLY / UNLOAD / RECONCILE / ROLLBACK) carry the
-- expected and desired revision so the Agent can fence stale and de-duplicate duplicate
-- commands. The agent_command table gains expected/desired revision and result-hash columns
-- so the existing command infrastructure carries the fencing tokens without a parallel queue.
--
-- Auto-unload no longer uses RESET_ALL: it is expressed as a chain operation whose desired
-- state excludes the target rule (or EMPTY), dispatched as APPLY_CHAIN / RESET_CLASS.

create table rule_chain_desired_state (
    id varchar(64) primary key,
    application_id varchar(64) not null,
    environment_id varchar(64) not null,
    agent_id varchar(64) not null,
    chain_id varchar(128) not null,
    target_class_name varchar(255) not null,
    target_method_name varchar(128) not null,
    target_method_descriptor varchar(255) not null,
    target_location varchar(32) not null,
    target_call_site_selector_json text,
    revision bigint not null default 0,
    canonical_hash varchar(64) not null,
    desired_state varchar(16) not null default 'ACTIVE',
    transformation_revision bigint not null default 0,
    rule_entries_json text not null,
    version bigint not null default 0,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_by varchar(255) not null,
    updated_at timestamp not null,
    constraint ck_rule_chain_desired_state check (desired_state in ('ACTIVE','EMPTY')),
    constraint uk_rule_chain_desired_state unique (application_id, environment_id, agent_id, chain_id)
);

create index idx_rule_chain_desired_target
    on rule_chain_desired_state(target_class_name, target_method_name, target_method_descriptor, target_location);

create table rule_chain_instance_state (
    id varchar(64) primary key,
    desired_state_id varchar(64) not null references rule_chain_desired_state(id),
    agent_id varchar(64) not null,
    applied_revision bigint not null default 0,
    applied_hash varchar(64) not null default '',
    transformation_revision bigint not null default 0,
    transformation_hash varchar(64),
    apply_time timestamp,
    degraded_reason varchar(255),
    status varchar(32) not null default 'UNKNOWN',
    version bigint not null default 0,
    updated_at timestamp not null,
    constraint uk_rule_chain_instance unique (desired_state_id, agent_id)
);

create table rule_chain_operation (
    id varchar(64) primary key,
    desired_state_id varchar(64) not null references rule_chain_desired_state(id),
    operation_type varchar(32) not null,
    expected_revision bigint not null,
    desired_revision bigint not null,
    desired_hash varchar(64) not null,
    status varchar(32) not null default 'PENDING',
    result_hash varchar(64),
    error_message text,
    version bigint not null default 0,
    created_by varchar(255) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    updated_by varchar(255) not null,
    constraint ck_rule_chain_operation_type check (operation_type in ('APPLY','UNLOAD','RECONCILE','ROLLBACK')),
    constraint ck_rule_chain_operation_status check (status in ('PENDING','DISPATCHED','APPLIED','STALE_COMMAND','IDEMPOTENT_REPLAY','COMPILE_FAILED','TRANSFORM_FAILED','VERIFICATION_FAILED','COEXISTENCE_UNSAFE','REJECTED','NO_OP','DEGRADED','ROLLED_BACK','FAILED'))
);

create table rule_chain_operation_target (
    id varchar(64) primary key,
    operation_id varchar(64) not null references rule_chain_operation(id),
    target_class_name varchar(255) not null,
    target_method_name varchar(128) not null,
    target_method_descriptor varchar(255) not null,
    target_location varchar(32) not null,
    target_call_site_selector_json text
);

create index idx_rule_chain_operation_state on rule_chain_operation(desired_state_id, status);

-- Carry the V1.4 fencing tokens on the existing command queue so APPLY_CHAIN commands
-- are self-describing: the Agent reads expected/desired revision and verifies the desired
-- hash, returning STALE_COMMAND or the prior result for duplicates.
alter table agent_command add column expected_revision bigint;
alter table agent_command add column desired_revision bigint;
alter table agent_command add column desired_hash varchar(64);
alter table agent_command add column result_hash varchar(64);
