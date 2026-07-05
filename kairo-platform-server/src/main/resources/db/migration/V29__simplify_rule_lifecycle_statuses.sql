update rule_version
   set status = 'ENABLED'
 where status <> 'DISABLED';

update rule
   set status = 'ENABLED'
 where status <> 'DISABLED';

update rule r
   set current_draft_version = (
           select rv.version
             from rule_version rv
            where rv.rule_id = r.id
              and rv.status = 'ENABLED'
            order by rv.version desc
            limit 1
       ),
       latest_version = (
           select rv.version
             from rule_version rv
            where rv.rule_id = r.id
              and rv.status = 'ENABLED'
            order by rv.version desc
            limit 1
       ),
       status = case
           when exists (
               select 1
                 from rule_version rv
                where rv.rule_id = r.id
                  and rv.status = 'ENABLED'
           ) then 'ENABLED'
           else 'DISABLED'
       end
 where exists (
       select 1
         from rule_version rv
        where rv.rule_id = r.id
 );

update rule r
   set current_draft_version = null,
       latest_version = null,
       status = 'DISABLED'
 where not exists (
       select 1
         from rule_version rv
        where rv.rule_id = r.id
          and rv.status = 'ENABLED'
 );

alter table rule
    drop constraint if exists chk_rule_status_lifecycle;

alter table rule
    add constraint chk_rule_status_lifecycle
    check (status in ('ENABLED', 'DISABLED'));

alter table rule_version
    drop constraint if exists chk_rule_version_status_lifecycle;

alter table rule_version
    add constraint chk_rule_version_status_lifecycle
    check (status in ('ENABLED', 'DISABLED'));
