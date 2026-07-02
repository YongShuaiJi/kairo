alter table rule
    add column disabled_at timestamp;

alter table rule
    add column auto_delete_at timestamp;

create index idx_rule_disabled_auto_delete
    on rule(status, auto_delete_at);
