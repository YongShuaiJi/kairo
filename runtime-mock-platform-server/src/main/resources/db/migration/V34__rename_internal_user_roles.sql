update role
   set name = 'SUPER_ADMIN',
       description = 'System super administrator'
 where id = 'role-admin';

update role
   set name = 'BUSINESS_USER',
       description = 'Business operation user'
 where id = 'role-operator';

delete from role_permission
 where role_id = 'role-auditor';

delete from role
 where id = 'role-auditor';
