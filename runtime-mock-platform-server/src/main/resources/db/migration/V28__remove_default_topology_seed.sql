delete from environment
 where application_id = 'app-default'
   and not exists (select 1 from instance where application_id = 'app-default')
   and not exists (select 1 from rule where application_id = 'app-default')
   and not exists (select 1 from operation_plan where application_id = 'app-default');

delete from application
 where id = 'app-default'
   and not exists (select 1 from environment where application_id = 'app-default')
   and not exists (select 1 from instance where application_id = 'app-default')
   and not exists (select 1 from rule where application_id = 'app-default')
   and not exists (select 1 from operation_plan where application_id = 'app-default');

delete from project
 where id = 'proj-default'
   and not exists (select 1 from application where project_id = 'proj-default');
