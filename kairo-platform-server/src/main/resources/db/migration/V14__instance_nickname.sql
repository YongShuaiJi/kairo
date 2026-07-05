alter table instance add column nickname varchar(255);

update instance i
   set nickname = (
       select a.name
         from application a
        where a.id = i.application_id
   )
 where nickname is null;

update instance
   set nickname = id
 where nickname is null or nickname = '';

update instance i
   set nickname = nickname || '-' || id
 where exists (
       select 1
         from instance other
        where other.nickname = i.nickname
          and other.id <> i.id
 );

create unique index uk_instance_nickname on instance(nickname);

alter table instance alter column nickname set not null;
