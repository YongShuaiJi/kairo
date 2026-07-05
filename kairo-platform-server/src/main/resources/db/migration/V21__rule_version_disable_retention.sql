alter table rule_version
    add column if not exists disabled_at timestamp;

alter table rule_version
    add column if not exists auto_delete_at timestamp;

alter table rule_version
    add column if not exists disabled_from_status varchar(64);

create index if not exists idx_rule_version_disable_retention
    on rule_version(status, auto_delete_at);

update rule_version rv
   set status = 'DISABLED',
       disabled_at = (
           select r.disabled_at
             from rule r
            where r.id = rv.rule_id
       ),
       auto_delete_at = (
           select r.auto_delete_at
             from rule r
            where r.id = rv.rule_id
       ),
       disabled_from_status = rv.status
 where exists (
       select 1
         from rule r
        where r.id = rv.rule_id
          and r.status = 'DISABLED'
          and r.disabled_at is not null
 )
   and rv.status <> 'DISABLED';

update rule
   set disabled_at = null,
       auto_delete_at = null
 where disabled_at is not null
    or auto_delete_at is not null;
