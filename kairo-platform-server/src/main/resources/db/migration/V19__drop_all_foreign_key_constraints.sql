alter table organization drop constraint if exists organization_enterprise_id_fkey;
alter table project drop constraint if exists project_organization_id_fkey;
alter table application drop constraint if exists application_project_id_fkey;
alter table environment drop constraint if exists environment_application_id_fkey;
alter table external_identity drop constraint if exists external_identity_user_id_fkey;
alter table role_permission drop constraint if exists role_permission_role_id_fkey;
alter table role_permission drop constraint if exists role_permission_permission_id_fkey;
alter table user_role_binding drop constraint if exists user_role_binding_user_id_fkey;
alter table user_role_binding drop constraint if exists user_role_binding_role_id_fkey;
alter table user_role_binding drop constraint if exists user_role_binding_scope_id_fkey;

alter table instance drop constraint if exists instance_application_id_fkey;
alter table instance drop constraint if exists instance_environment_id_fkey;
alter table instance_label drop constraint if exists instance_label_instance_id_fkey;
alter table asset_claim drop constraint if exists asset_claim_instance_id_fkey;
alter table sidecar_instance drop constraint if exists sidecar_instance_instance_id_fkey;
alter table sidecar_instance drop constraint if exists sidecar_instance_executor_id_fkey;
alter table agent_instance drop constraint if exists agent_instance_instance_id_fkey;
alter table agent_instance drop constraint if exists agent_instance_sidecar_id_fkey;
alter table agent_registration drop constraint if exists agent_registration_agent_id_fkey;
alter table agent_capability drop constraint if exists agent_capability_agent_id_fkey;
alter table agent_heartbeat drop constraint if exists agent_heartbeat_agent_id_fkey;
alter table degraded_class drop constraint if exists degraded_class_agent_id_fkey;

alter table rule drop constraint if exists rule_application_id_fkey;
alter table rule drop constraint if exists rule_environment_id_fkey;
alter table rule_version drop constraint if exists rule_version_rule_id_fkey;
alter table rule_target drop constraint if exists rule_target_rule_version_id_fkey;
alter table rule_capability drop constraint if exists rule_capability_rule_version_id_fkey;
alter table rule_lock drop constraint if exists rule_lock_rule_id_fkey;
alter table rule_runtime_status drop constraint if exists rule_runtime_status_rule_id_fkey;
alter table rule_runtime_status drop constraint if exists rule_runtime_status_instance_id_fkey;
alter table rule_instance_binding drop constraint if exists rule_instance_binding_rule_id_fkey;
alter table rule_instance_binding drop constraint if exists rule_instance_binding_instance_id_fkey;

alter table operation_plan drop constraint if exists operation_plan_application_id_fkey;
alter table operation_plan drop constraint if exists operation_plan_environment_id_fkey;
alter table operation_plan drop constraint if exists operation_plan_approval_id_fkey;
alter table rollout_plan drop constraint if exists rollout_plan_operation_plan_id_fkey;
alter table rollout_batch drop constraint if exists rollout_batch_operation_plan_id_fkey;
alter table rollout_target_snapshot drop constraint if exists rollout_target_snapshot_operation_plan_id_fkey;
alter table rollout_target_snapshot drop constraint if exists rollout_target_snapshot_instance_id_fkey;
alter table rollout_instance_execution drop constraint if exists rollout_instance_execution_rollout_batch_id_fkey;
alter table rollout_instance_execution drop constraint if exists rollout_instance_execution_instance_id_fkey;
alter table rollout_instance_execution drop constraint if exists rollout_instance_execution_operation_plan_id_fkey;
alter table rollback_execution drop constraint if exists rollback_execution_operation_plan_id_fkey;
alter table observation_window drop constraint if exists observation_window_operation_plan_id_fkey;
alter table external_health_signal drop constraint if exists external_health_signal_operation_plan_id_fkey;

alter table agent_command drop constraint if exists agent_command_agent_id_fkey;
alter table agent_command drop constraint if exists agent_command_rollback_execution_id_fkey;

alter table attach_executor_target drop constraint if exists attach_executor_target_executor_id_fkey;
alter table attach_executor_target drop constraint if exists attach_executor_target_instance_id_fkey;
alter table attach_executor_command drop constraint if exists attach_executor_command_executor_id_fkey;
alter table attach_executor_command drop constraint if exists attach_executor_command_instance_id_fkey;
