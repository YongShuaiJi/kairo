alter table rollout_instance_execution
    add column operation_plan_id varchar(64) references operation_plan(id);

update rollout_instance_execution execution
   set operation_plan_id = (
       select batch.operation_plan_id
         from rollout_batch batch
        where batch.id = execution.rollout_batch_id
   )
 where operation_plan_id is null;

alter table rollout_instance_execution
    alter column operation_plan_id set not null;

alter table rollout_instance_execution
    alter column rollout_batch_id drop not null;

create index idx_rollout_execution_operation_status
    on rollout_instance_execution(operation_plan_id, status, id);

alter table payload_object
    add column metadata_json text not null default '{}';

alter table dataset_object_reference
    add column metadata_json text not null default '{}';

update dataset_object_reference dataset_ref
   set metadata_json = coalesce((
       select artifact.metadata_json
         from worker_artifact artifact
        where artifact.object_uri = dataset_ref.object_uri
        order by artifact.created_at desc
        limit 1
   ), metadata_json)
 where metadata_json = '{}';

update dataset_object_reference dataset_ref
   set metadata_json = coalesce((
       select payload.metadata_json
         from payload_object payload
        where payload.object_uri = dataset_ref.object_uri
        order by payload.created_at desc
        limit 1
   ), metadata_json)
 where metadata_json = '{}';
