alter table agent_instance
    drop constraint if exists agent_instance_instance_id_fkey;

alter table asset_claim
    drop constraint if exists asset_claim_instance_id_fkey;

alter table attach_executor_command
    drop constraint if exists attach_executor_command_instance_id_fkey;

alter table attach_executor_target
    drop constraint if exists attach_executor_target_instance_id_fkey;

alter table instance_label
    drop constraint if exists instance_label_instance_id_fkey;

alter table rule_instance_binding
    drop constraint if exists rule_instance_binding_instance_id_fkey;

alter table rule_runtime_status
    drop constraint if exists rule_runtime_status_instance_id_fkey;

alter table sidecar_instance
    drop constraint if exists sidecar_instance_instance_id_fkey;
