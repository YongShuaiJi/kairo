alter table agent_command
    add column rollback_execution_id varchar(64) references rollback_execution(id);

create index idx_agent_command_rollback_status
    on agent_command(rollback_execution_id, status, id);
