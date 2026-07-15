-- V1.6 acceptance safety: idempotency reservation/in-progress/completed protocol.
-- The previous check-then-execute-then-insert filter could execute a mutation twice
-- under concurrent same-key requests. This adds a status column (IN_PROGRESS vs
-- COMPLETED), a lease_expires_at column (the owning node renews it while executing so
-- a live owner is never reclaimed), and an owner_token column (a unique fencing token
-- per reservation so a stale owner whose lease was reclaimed can never complete or
-- delete the new owner's row). A same-key same-request waiter replays the completed
-- result instead of re-executing; an expired (past expires_at) row is cleaned up so
-- the key can be reused instead of colliding on the primary key forever.
alter table idempotency_record add column status varchar(32) not null default 'COMPLETED';
alter table idempotency_record add column lease_expires_at timestamp;
alter table idempotency_record add column owner_token varchar(64);

-- Pre-existing rows are completed results (no owner token).
update idempotency_record set status = 'COMPLETED' where status is null or status = '';

-- Speed up reclaim/conflict probes for in-progress reservations.
create index idx_idempotency_status_lease on idempotency_record(status, lease_expires_at);
