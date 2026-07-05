delete from platform_access_token
 where id in (
       select id
         from (
              select id,
                     row_number() over (
                       partition by subject_type, subject_id
                       order by created_at desc, id desc
                     ) as row_number
                from platform_access_token
               where subject_type = 'USER'
         ) ranked
        where row_number > 1
 );
