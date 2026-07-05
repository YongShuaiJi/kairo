alter table rollout_target_snapshot
    add column instance_nickname varchar(255) not null default '';

alter table rollout_target_snapshot
    add column application_name varchar(255) not null default '';

alter table rollout_target_snapshot
    add column environment_name varchar(255) not null default '';

alter table rollout_target_snapshot
    add column java_version varchar(128) not null default '';

alter table rollout_target_snapshot
    add column agent_version varchar(128) not null default '';

alter table rollout_target_snapshot
    add column load_mode varchar(64) not null default '';

alter table rollout_target_snapshot
    add column process_start_id varchar(512) not null default '';

alter table rollout_target_snapshot
    add column instance_last_seen_at timestamp;

alter table rollout_target_snapshot
    add column attach_executor_id varchar(64) not null default '';

alter table rollout_instance_execution
    add column instance_nickname varchar(255) not null default '';

alter table rollout_instance_execution
    add column application_name varchar(255) not null default '';

alter table rollout_instance_execution
    add column environment_name varchar(255) not null default '';

alter table rollout_instance_execution
    add column java_version varchar(128) not null default '';

alter table rollout_instance_execution
    add column agent_version varchar(128) not null default '';

alter table rollout_instance_execution
    add column load_mode varchar(64) not null default '';

alter table rollout_instance_execution
    add column process_start_id varchar(512) not null default '';

alter table rollout_instance_execution
    add column instance_last_seen_at timestamp;

alter table rollout_instance_execution
    add column attach_executor_id varchar(64) not null default '';

update rollout_target_snapshot snapshot
   set instance_nickname = coalesce((
           select i.nickname
             from instance i
            where i.id = snapshot.instance_id
       ), snapshot.instance_id),
       application_name = coalesce((
           select a.name
             from instance i
             left join application a on a.id = i.application_id
            where i.id = snapshot.instance_id
       ), ''),
       environment_name = coalesce((
           select coalesce(e.type, e.name)
             from instance i
             left join environment e on e.id = i.environment_id
            where i.id = snapshot.instance_id
       ), ''),
       java_version = coalesce((
           select i.java_version
             from instance i
            where i.id = snapshot.instance_id
       ), ''),
       agent_version = coalesce((
           select i.agent_version
             from instance i
            where i.id = snapshot.instance_id
       ), ''),
       load_mode = coalesce((
           select i.load_mode
             from instance i
            where i.id = snapshot.instance_id
       ), ''),
       process_start_id = coalesce((
           select i.process_start_id
             from instance i
            where i.id = snapshot.instance_id
       ), ''),
       instance_last_seen_at = (
           select i.last_seen_at
             from instance i
            where i.id = snapshot.instance_id
       ),
       attach_executor_id = coalesce((
           select t.executor_id
             from attach_executor_target t
            where t.instance_id = snapshot.instance_id
            order by case when t.last_seen_at is null then 1 else 0 end,
                     t.last_seen_at desc,
                     t.updated_at desc,
                     t.executor_id
            limit 1
       ), '');

update rollout_instance_execution execution
   set instance_nickname = coalesce((
           select i.nickname
             from instance i
            where i.id = execution.instance_id
       ), execution.instance_id),
       application_name = coalesce((
           select a.name
             from instance i
             left join application a on a.id = i.application_id
            where i.id = execution.instance_id
       ), ''),
       environment_name = coalesce((
           select coalesce(e.type, e.name)
             from instance i
             left join environment e on e.id = i.environment_id
            where i.id = execution.instance_id
       ), ''),
       java_version = coalesce((
           select i.java_version
             from instance i
            where i.id = execution.instance_id
       ), ''),
       agent_version = coalesce((
           select i.agent_version
             from instance i
            where i.id = execution.instance_id
       ), ''),
       load_mode = coalesce((
           select i.load_mode
             from instance i
            where i.id = execution.instance_id
       ), ''),
       process_start_id = coalesce((
           select i.process_start_id
             from instance i
            where i.id = execution.instance_id
       ), ''),
       instance_last_seen_at = (
           select i.last_seen_at
             from instance i
            where i.id = execution.instance_id
       ),
       attach_executor_id = coalesce((
           select t.executor_id
             from attach_executor_target t
            where t.instance_id = execution.instance_id
            order by case when t.last_seen_at is null then 1 else 0 end,
                     t.last_seen_at desc,
                     t.updated_at desc,
                     t.executor_id
            limit 1
       ), '');

alter table rollout_target_snapshot
    drop constraint if exists rollout_target_snapshot_instance_id_fkey;

alter table rollout_instance_execution
    drop constraint if exists rollout_instance_execution_instance_id_fkey;
