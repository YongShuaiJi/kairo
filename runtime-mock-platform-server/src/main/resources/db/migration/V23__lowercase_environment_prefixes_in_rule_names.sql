update rule
   set name = 'dev' || substring(name from 4)
 where name like 'DEV %';

update rule
   set name = 'sit' || substring(name from 4)
 where name like 'SIT %';

update rule
   set name = 'uat' || substring(name from 4)
 where name like 'UAT %';

update rule
   set name = 'prod' || substring(name from 5)
 where name like 'PROD %';
