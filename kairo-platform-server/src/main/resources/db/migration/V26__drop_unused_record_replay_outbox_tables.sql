alter table if exists operation_plan drop column if exists approval_id;

drop table if exists comparison_result cascade;
drop table if exists replay_invocation_result cascade;
drop table if exists replay_batch cascade;
drop table if exists replay_execution cascade;
drop table if exists comparison_policy cascade;
drop table if exists replay_side_effect_policy cascade;
drop table if exists replay_target cascade;
drop table if exists replay_plan_version cascade;
drop table if exists replay_cleanup_execution cascade;
drop table if exists replay_plan cascade;

drop table if exists extraction_quota cascade;
drop table if exists extraction_result cascade;
drop table if exists extraction_execution cascade;
drop table if exists extraction_task cascade;
drop table if exists extraction_relation cascade;
drop table if exists extraction_template_version cascade;
drop table if exists extraction_template cascade;
drop table if exists datasource_credential_ref cascade;
drop table if exists datasource_registration cascade;

drop table if exists data_deletion_execution cascade;
drop table if exists data_retention_policy cascade;
drop table if exists legal_hold cascade;
drop table if exists cleanup_manifest cascade;
drop table if exists identity_mapping cascade;
drop table if exists dataset_object_reference cascade;
drop table if exists dataset_manifest cascade;
drop table if exists dataset_schema cascade;
drop table if exists dataset_source_session cascade;
drop table if exists dataset_version cascade;
drop table if exists dataset cascade;

drop table if exists recording_event_index cascade;
drop table if exists payload_reference cascade;
drop table if exists payload_object cascade;
drop table if exists recording_batch cascade;
drop table if exists recording_session_quota cascade;
drop table if exists recording_session_target cascade;
drop table if exists recording_rule_version cascade;
drop table if exists tokenization_policy cascade;
drop table if exists masking_policy cascade;
drop table if exists recording_rule cascade;
drop table if exists recording_session cascade;

drop table if exists approval_decision cascade;
drop table if exists approval_step cascade;
drop table if exists approval_request cascade;

drop table if exists outbox_event cascade;
drop table if exists worker_artifact cascade;
drop table if exists observation_window cascade;
drop table if exists external_health_signal cascade;

delete from role_permission
 where permission_id in (
       select id
         from permission
        where capability in (
              'RECORD_ARGUMENTS',
              'RECORD_RETURN',
              'DATA_EXTRACT',
              'IMPORT_TO_TEST',
              'REPLAY_EXECUTE',
              'EXPORT_DATA',
              'APPROVE',
              'RESET'
        )
 );

delete from permission
 where capability in (
       'RECORD_ARGUMENTS',
       'RECORD_RETURN',
       'DATA_EXTRACT',
       'IMPORT_TO_TEST',
       'REPLAY_EXECUTE',
       'EXPORT_DATA',
       'APPROVE',
       'RESET'
 );
