alter table instance alter column environment_id drop not null;

alter table instance add column process_start_id varchar(512);
alter table instance add column jvm_started_at timestamp;
alter table instance add column java_version varchar(128);
alter table instance add column load_mode varchar(64);
alter table instance add column agent_version varchar(128);
alter table instance add column capabilities_json text not null default '[]';
alter table instance add column lease_expires_at timestamp;
alter table instance add column registration_status varchar(64) not null default 'ASSIGNED';

create unique index uk_instance_process_start_id
    on instance(process_start_id);
create index idx_instance_lease_status on instance(status, lease_expires_at);

alter table agent_instance add column lease_expires_at timestamp;
create index idx_agent_lease_status on agent_instance(status, lease_expires_at);

update environment set type = 'DEV' where id = 'env-dev';

insert into environment(id, application_id, name, type)
select 'env-sit', 'app-default', 'sit', 'SIT'
where not exists (
    select 1 from environment where application_id = 'app-default' and name = 'sit'
);

insert into environment(id, application_id, name, type)
select 'env-uat', 'app-default', 'uat', 'UAT'
where not exists (
    select 1 from environment where application_id = 'app-default' and name = 'uat'
);

insert into environment(id, application_id, name, type)
select 'env-prod', 'app-default', 'prod', 'PROD'
where not exists (
    select 1 from environment where application_id = 'app-default' and name = 'prod'
);

update environment set type = 'SIT'
where application_id = 'app-default' and name = 'sit';
update environment set type = 'UAT'
where application_id = 'app-default' and name = 'uat';
update environment set type = 'PROD'
where application_id = 'app-default' and name = 'prod';
