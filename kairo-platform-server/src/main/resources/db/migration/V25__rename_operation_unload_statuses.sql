update operation_plan
   set status = case status
       when 'ROLLING_BACK' then 'UNLOADING'
       when 'ROLLED_BACK' then 'UNLOADED'
       else status
   end,
       updated_at = current_timestamp
 where status in ('ROLLING_BACK', 'ROLLED_BACK');

update rollout_instance_execution
   set status = 'UNLOADED',
       updated_at = current_timestamp
 where status = 'ROLLED_BACK';

update operation_plan
   set strategy_json = replace(strategy_json, '"automaticRollback"', '"automaticUnload"')
 where strategy_json like '%"automaticRollback"%';
