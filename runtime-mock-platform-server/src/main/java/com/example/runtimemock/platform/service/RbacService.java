package com.example.runtimemock.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                  join resource_scope rs on rs.id = urb.scope_id
                  join role_permission rp on rp.role_id = urb.role_id
                  join permission p on p.id = rp.permission_id
                 where u.username = ?
                   and u.status = 'ACTIVE'
                   and (urb.expires_at is null or urb.expires_at > current_timestamp)
                   and (p.capability = ? or p.capability = 'ADMIN')
                   and rs.resource_type = 'GLOBAL'
                   and rs.resource_id = '*'
                """, Integer.class, context.actor(), capability);
        if (count == null || count == 0) {
            throw PlatformException.forbidden(capability);
        }
    }

    public void require(RequestContext context, String capability, String resourceType, String resourceId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from user_account u
                  join user_role_binding urb on urb.user_id = u.id
                  join resource_scope rs on rs.id = urb.scope_id
                  join role_permission rp on rp.role_id = urb.role_id
                  join permission p on p.id = rp.permission_id
                 where u.username = ?
                   and u.status = 'ACTIVE'
                   and (urb.expires_at is null or urb.expires_at > current_timestamp)
                   and (p.capability = ? or p.capability = 'ADMIN')
                   and (
                        (rs.resource_type = 'GLOBAL' and rs.resource_id = '*')
                        or (rs.resource_type = ? and rs.resource_id = ?)
                   )
                """, Integer.class, context.actor(), capability, resourceType, resourceId);
        if (count == null || count == 0) {
            throw PlatformException.forbidden(capability);
        }
    }

    public Map<String, Object> describe(String username) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> user = jdbcTemplate.queryForMap("""
                select username, display_name, status
                  from user_account
                 where username = ? and status = 'ACTIVE'
                """, username);
        result.put("subject", user.get("username"));
        result.put("displayName", user.get("display_name"));
        List<String> roles = jdbcTemplate.queryForList("""
                select distinct r.name
                  from user_account u
                  join user_role_binding urb on urb.user_id = u.id
                  join role r on r.id = urb.role_id
                 where u.username = ?
                   and (urb.expires_at is null or urb.expires_at > current_timestamp)
                 order by r.name
                """, String.class, username);
        List<String> capabilities = jdbcTemplate.queryForList("""
                select distinct p.capability
                  from user_account u
                  join user_role_binding urb on urb.user_id = u.id
                  join role_permission rp on rp.role_id = urb.role_id
                  join permission p on p.id = rp.permission_id
                 where u.username = ?
                   and (urb.expires_at is null or urb.expires_at > current_timestamp)
                 order by p.capability
                """, String.class, username);
        List<Map<String, Object>> scopes = jdbcTemplate.queryForList("""
                select distinct rs.resource_type, rs.resource_id
                  from user_account u
                  join user_role_binding urb on urb.user_id = u.id
                  join resource_scope rs on rs.id = urb.scope_id
                 where u.username = ?
                   and (urb.expires_at is null or urb.expires_at > current_timestamp)
                 order by rs.resource_type, rs.resource_id
                """, username);
        result.put("roles", roles);
        result.put("capabilities", capabilities);
        result.put("scopes", scopes);
        return result;
    }

    public Map<String, Object> describeAgent(String agentId) {
        Map<String, Object> agent = jdbcTemplate.queryForMap("""
                select id, status
                  from agent_instance
                 where id = ? and status <> 'REMOVED'
                """, agentId);
        List<String> capabilities = jdbcTemplate.queryForList("""
                select capability
                  from agent_capability
                 where agent_id = ?
                 order by capability
                """, String.class, agentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", agent.get("id"));
        result.put("displayName", agent.get("id"));
        result.put("roles", List.of("AGENT"));
        result.put("capabilities", capabilities);
        result.put("scopes", List.of(Map.of("resource_type", "AGENT", "resource_id", agentId)));
        return result;
    }
}
