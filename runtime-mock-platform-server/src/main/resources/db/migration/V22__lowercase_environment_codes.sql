update environment
   set type = lower(type),
       name = lower(name)
 where upper(type) in ('DEV', 'SIT', 'UAT', 'PROD')
    or upper(name) in ('DEV', 'SIT', 'UAT', 'PROD');

update rollout_target_snapshot
   set environment_name = lower(environment_name)
 where upper(environment_name) in ('DEV', 'SIT', 'UAT', 'PROD');

update rollout_instance_execution
   set environment_name = lower(environment_name)
 where upper(environment_name) in ('DEV', 'SIT', 'UAT', 'PROD');
