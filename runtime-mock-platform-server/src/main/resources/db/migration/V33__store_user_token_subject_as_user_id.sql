update platform_access_token token
   set subject_id = account.id,
       display_name = account.display_name
  from user_account account
 where token.subject_type = 'USER'
   and token.subject_id = account.username;

delete from platform_access_token token
 where token.subject_type = 'USER'
   and not exists (
       select 1
         from user_account account
        where account.id = token.subject_id
          and account.status = 'ACTIVE'
   );

delete from platform_access_token
 where id in (
       select id
         from (
              select id,
                     row_number() over (
                       partition by subject_type, subject_id
                       order by case when id = 'token-bootstrap' then 0 else 1 end,
                                created_at desc,
                                id desc
                     ) as row_number
                from platform_access_token
               where subject_type = 'USER'
         ) ranked
        where row_number > 1
 );
