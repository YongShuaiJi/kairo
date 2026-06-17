package com.example.runtimemock.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public final class RbacService {

    private final JdbcTemplate jdbcTemplate;

    public RbacService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void require(RequestContext context, String capability) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from user_account u
                  join user_role_binding urb on urb.user_id = u.id
                  join role_permission rp on rp.role_id = urb.role_id
                  join permission p on p.id = rp.permission_id
                 where u.username = ?
                   and u.status = 'ACTIVE'
                   and (urb.expires_at is null or urb.expires_at > current_timestamp)
                   and (p.capability = ? or p.capability = 'ADMIN')
                """, Integer.class, context.actor(), capability);
        if (count == null || count == 0) {
            throw PlatformException.forbidden(capability);
        }
    }
}
