package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.persistence.mapper.AccessControlMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public final class RbacService {

    private final AccessControlMapper accessControlMapper;

    public RbacService(AccessControlMapper accessControlMapper) {
        this.accessControlMapper = accessControlMapper;
    }

    public void require(RequestContext context, String capability) {
        if (accessControlMapper.countGlobalCapability(context.actor(), capability) == 0) {
            throw PlatformException.forbidden(capability);
        }
    }

    public void require(RequestContext context, String capability, String resourceType, String resourceId) {
        if (accessControlMapper.countScopedCapability(context.actor(), capability, resourceType, resourceId) == 0) {
            throw PlatformException.forbidden(capability);
        }
    }

    public Map<String, Object> describe(String username) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> user = accessControlMapper.activeUser(username);
        result.put("subject", user.get("username"));
        result.put("displayName", user.get("display_name"));
        List<String> roles = accessControlMapper.roles(username);
        List<String> capabilities = accessControlMapper.capabilities(username);
        List<Map<String, Object>> scopes = accessControlMapper.scopes(username);
        result.put("roles", roles);
        result.put("capabilities", capabilities);
        result.put("scopes", scopes);
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
}
