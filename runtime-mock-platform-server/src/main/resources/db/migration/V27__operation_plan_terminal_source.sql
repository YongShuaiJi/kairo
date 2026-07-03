alter table operation_plan
    add column terminal_source varchar(64) not null default '';

alter table operation_plan
    add column terminal_reason text not null default '';

create index idx_operation_plan_terminal_source
    on operation_plan(status, terminal_source);
