-- V1.7 M1-C §8.3: persist the latest bounded Agent runtime-state snapshot per agent.
--
-- A REFRESH_RUNTIME_STATE command ACK carries a bounded, read-only snapshot of the Agent's
-- in-memory runtime state (rules, chains, degraded classes, disabled flag, revisions/hashes and
-- structured truncation metadata). The Platform strictly validates the snapshot (schema, protocol
-- version, command type, agentId, processStartId, collection bounds, serialized byte size) inside
-- the existing ACK transaction, then persists exactly one current snapshot per agent here.
--
-- Storage is bounded: one row per agent_id (primary key), replaced on each accepted REFRESH ack
-- (delete + insert inside the ACK transaction). snapshot_json is validated to be <= 1 MiB in Java
-- before insert; there is no append-only history (M1-D reconciliation reads this single current
-- row). A stale snapshot whose processStartId no longer matches the registered instance is rejected
-- before persistence, so a late ACK from an old process never overwrites the current actual state.
--
-- Referential integrity: each snapshot belongs to a registered agent/instance. The foreign keys
-- cascade on delete so the existing maintenance paths (deleteOfflineAgents hard-deletes
-- agent_instance) remove a stale snapshot automatically without a manual cleanup step, and an
-- archived (soft) instance row never violates the constraint.
create table agent_runtime_state (
    agent_id varchar(64) not null,
    instance_id varchar(64) not null,
    process_start_id varchar(512) not null,
    protocol_version varchar(32) not null,
    agent_version varchar(128),
    observed_at timestamp not null,
    received_at timestamp not null,
    disabled boolean not null,
    rule_count integer not null,
    chain_count integer not null,
    degraded_class_count integer not null,
    serialized_bytes integer not null,
    snapshot_json text not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (agent_id),
    foreign key (agent_id) references agent_instance(id) on delete cascade,
    foreign key (instance_id) references instance(id) on delete cascade
);

create index idx_agent_runtime_state_process on agent_runtime_state(process_start_id);
