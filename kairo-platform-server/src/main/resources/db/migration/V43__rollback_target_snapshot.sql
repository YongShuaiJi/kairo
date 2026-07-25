-- V1.7 M1-E: a precise unload must retry the target captured when the rollback was created.
-- rule_target may later be disabled or deleted, so resolving it again during compensation can
-- expand or misdirect the unload. Existing rollback rows predate offline compensation and keep
-- empty snapshots; new M1-E rows always persist both values before dispatch/pending state begins.
alter table rollback_execution
    add column target_class_id varchar(1024) not null default '';

alter table rollback_execution
    add column target_class_name varchar(1024) not null default '';
