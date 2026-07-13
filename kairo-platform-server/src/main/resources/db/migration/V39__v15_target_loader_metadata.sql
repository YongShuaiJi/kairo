-- V1.5 §5: persist modern-JVM target metadata on rule targets.
--
-- V1.5 requires the platform to save, for each enhancement target, the loader / module /
-- code source / proxy type / support level the agent observed, the last time it was
-- observed, and the drift status after a hot update. The rule_target row is the natural
-- home: it already carries class_name, method_name, matcher_json, location and the
-- call-site selector (V1.3). These columns let the Web class selector render the loader
-- tree / module / code source / proxy type and let the platform surface TARGET_DRIFTED
-- without re-resolving against the agent.
--
-- Every column is nullable: legacy V1.0/V1.2/V1.3/V1.4 rule targets pre-date V1.5
-- enrichment and keep NULL, so existing rules run unchanged with no data migration. New
-- resolutions (V1.5 RESOLVE_TARGET) stamp the columns; the read side degrades gracefully
-- when they are absent.

alter table rule_target add column loader_class varchar(512);
alter table rule_target add column class_loader_id varchar(128);
alter table rule_target add column module_name varchar(255);
alter table rule_target add column named_module boolean;
alter table rule_target add column code_source varchar(1024);
alter table rule_target add column proxy_type varchar(32);
alter table rule_target add column support_level varchar(32);
alter table rule_target add column framework_loader varchar(255);
alter table rule_target add column last_observed_at timestamp;
alter table rule_target add column drift_status varchar(32);

create index idx_rule_target_class_loader_id on rule_target(class_loader_id);
create index idx_rule_target_support_level on rule_target(support_level);
create index idx_rule_target_drift_status on rule_target(drift_status);

alter table rule_target add constraint ck_rule_target_proxy_type check (
    proxy_type is null or proxy_type in ('PLAIN', 'JDK_PROXY', 'CGLIB', 'BYTE_BUDDY', 'UNKNOWN')
);

alter table rule_target add constraint ck_rule_target_support_level check (
    support_level is null or support_level in ('SUPPORTED', 'LIMITED', 'EXPERIMENTAL', 'UNSUPPORTED')
);

alter table rule_target add constraint ck_rule_target_drift_status check (
    drift_status is null or drift_status in ('FRESH', 'DRIFTED', 'UNRESOLVED')
);
