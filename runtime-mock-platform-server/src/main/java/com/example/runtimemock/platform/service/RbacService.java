package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.persistence.mapper.AccessControlMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public final class RbacService {

    private static final List<String> BUSINESS_CAPABILITIES = List.of(
            "INSTANCE_MANAGE",
            "AGENT_MANAGE",
            "RULE_MANAGE",
            "ROLLOUT_MANAGE"
    );
    private static final List<String> SUPER_ADMIN_CAPABILITIES = List.of(
            "ADMIN",
            "USER_MANAGE",
            "INSTANCE_MANAGE",
            "AGENT_MANAGE",
            "RULE_MANAGE",
            "ROLLOUT_MANAGE"
    );
    private static final Set<String> USER_ADMIN_CAPABILITIES = Set.of("ADMIN", "USER_MANAGE");

    private final AccessControlMapper accessControlMapper;

    public RbacService(AccessControlMapper accessControlMapper) {
        this.accessControlMapper = accessControlMapper;
    }

    public void require(RequestContext context, String capability) {
        if (!allowed(context.actor(), capability)) {
            throw PlatformException.forbidden(capability);
        }
    }

    public void require(RequestContext context, String capability, String resourceType, String resourceId) {
        if (!allowed(context.actor(), capability)) {
            throw PlatformException.forbidden(capability);
        }
    }

    public Map<String, Object> describe(String username) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> user = accessControlMapper.activeUser(username);
        if (user == null) {
            throw PlatformException.notFound("user", username);
        }
        boolean superAdmin = isSuperAdmin(username);
        result.put("subject", user.get("username"));
        result.put("displayName", user.get("display_name"));
        result.put("roles", superAdmin ? List.of("SUPER_ADMIN") : List.of("BUSINESS_USER"));
        result.put("capabilities", superAdmin ? SUPER_ADMIN_CAPABILITIES : BUSINESS_CAPABILITIES);
        result.put("scopes", List.of(Map.of("resource_type", "GLOBAL", "resource_id", "*")));
        result.put("superAdmin", superAdmin);
        return result;
    }

    public Map<String, Object> describeAgent(String agentId) {
        Map<String, Object> agent = accessControlMapper.activeAgent(agentId);
        List<String> capabilities = accessControlMapper.agentCapabilities(agentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", agent.get("id"));
        result.put("displayName", agent.get("id"));
        result.put("roles", List.of("AGENT"));
        result.put("capabilities", capabilities);
        result.put("scopes", List.of(Map.of("resource_type", "AGENT", "resource_id", agentId)));
        return result;
    }

    public boolean isSuperAdmin(String username) {
        return accessControlMapper.countSuperAdmin(username) > 0;
    }

    private boolean allowed(String username, String capability) {
        if (isSuperAdmin(username)) {
            return true;
        }
        if (capability == null || USER_ADMIN_CAPABILITIES.contains(capability)) {
            return false;
        }
        return BUSINESS_CAPABILITIES.contains(capability)
                && accessControlMapper.countActiveUser(username) > 0;
    }
}
