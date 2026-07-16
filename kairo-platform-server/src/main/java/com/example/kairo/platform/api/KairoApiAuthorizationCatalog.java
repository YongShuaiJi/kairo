package com.example.kairo.platform.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Public API authorization contract used by OpenAPI and the V1 compatibility gate.
 *
 * <p>Authentication is inherited from the global bearer requirement.  This catalog records the
 * additional runtime RBAC rule (including conditional/self/resource-scoped rules) so a maintenance
 * release cannot silently require a stronger capability while leaving the HTTP/schema contract
 * unchanged.  Operations not listed in {@link #OVERRIDES} require an authenticated caller only.
 */
public final class KairoApiAuthorizationCatalog {

    public static final String EXTENSION = "x-kairo-authorization";
    public static final String AUTHENTICATED = "AUTHENTICATED";

    public record Route(String method, String path) {
        public Route {
            method = requireText(method, "method").toLowerCase(Locale.ROOT);
            path = requireText(path, "path");
        }
    }

    private static final Map<Route, String> OVERRIDES = build();

    private KairoApiAuthorizationCatalog() {
    }

    public static String requirement(String method, String path) {
        return OVERRIDES.getOrDefault(new Route(method, path), AUTHENTICATED);
    }

    public static Map<Route, String> overrides() {
        return OVERRIDES;
    }

    public static Set<String> declaredExpressions() {
        return Set.copyOf(OVERRIDES.values());
    }

    private static Map<Route, String> build() {
        Map<Route, String> out = new LinkedHashMap<>();

        // Agent control. Agent protocol endpoints permit the matching agent identity or a manager.
        put(out, "post", "/api/v1/agent-commands/{id}/ack", "SELF_AGENT_OR_AGENT_MANAGE");
        put(out, "post", "/api/v1/agent-registrations/self", "AGENT_MANAGE");
        put(out, "post", "/api/v1/agents", "AGENT_MANAGE");
        put(out, "post", "/api/v1/agents/{id}/commands", "AGENT_MANAGE");
        put(out, "post", "/api/v1/agents/{id}/commands/next", "SELF_AGENT_OR_AGENT_MANAGE");
        put(out, "post", "/api/v1/agents/{id}/heartbeat", "SELF_AGENT_OR_AGENT_MANAGE");
        for (String suffix : Set.of("bytecode", "capture", "diff", "preview", "transformations")) {
            String method = Set.of("capture", "preview").contains(suffix) ? "post" : "get";
            put(out, method, "/api/v1/agents/{agentId}/classes/{classId}/" + suffix,
                    "AGENT_MANAGE@agent_instance:{agentId}");
        }
        put(out, "post", "/api/v1/attach-executor-commands/{id}/ack", "AGENT_MANAGE");
        put(out, "post", "/api/v1/attach-executors/{id}/commands/next", "AGENT_MANAGE");
        put(out, "post", "/api/v1/attach-executors/{id}/heartbeat", "AGENT_MANAGE");
        put(out, "post", "/api/v1/attach-sidecars/self", "AGENT_MANAGE");
        put(out, "post", "/api/v1/instances/{id}/agent/attach", "AGENT_MANAGE");
        put(out, "post", "/api/v1/instances/{id}/agent/deactivate", "AGENT_MANAGE");
        put(out, "post", "/api/v1/instances/{id}/agent/reload", "AGENT_MANAGE");
        put(out, "post", "/api/v1/sidecars", "AGENT_MANAGE");

        // Topology and delivery.
        put(out, "post", "/api/v1/instances", "INSTANCE_MANAGE");
        put(out, "post", "/api/v1/instances/{id}/environment", "INSTANCE_MANAGE");
        put(out, "patch", "/api/v1/instances/{id}/nickname", "INSTANCE_MANAGE");
        put(out, "post", "/api/v1/operation-plans", "ROLLOUT_MANAGE");
        put(out, "post", "/api/v1/operation-plans/{id}/transition", "ROLLOUT_MANAGE");
        put(out, "post", "/api/v1/operation-plans/{id}/unload", "ROLLOUT_MANAGE");
        put(out, "post", "/api/v1/fencing-tokens",
                "BY_RESOURCE_TYPE{operation_plan|rollout_batch|rollout_instance_execution="
                        + "ROLLOUT_MANAGE;rule|rule_version=RULE_MANAGE;"
                        + "agent_instance|sidecar_instance=AGENT_MANAGE;default=ADMIN}");

        // Rule/script authoring. Deliberately omit aggregate/manual-delete endpoints that always
        // return METHOD_NOT_ALLOWED before any RBAC decision.
        put(out, "post", "/api/v1/rules", "RULE_MANAGE");
        put(out, "post", "/api/v1/rules/preview", "RULE_MANAGE");
        put(out, "post", "/api/v1/rules/{id}/versions", "RULE_MANAGE");
        put(out, "post", "/api/v1/rules/{id}/versions/{version}/disable", "RULE_MANAGE");
        put(out, "post", "/api/v1/rules/{id}/versions/{version}/enable", "RULE_MANAGE");
        put(out, "get", "/api/v1/apps/{appId}/script-policy", "RULE_MANAGE");
        put(out, "put", "/api/v1/apps/{appId}/script-policy", "RULE_MANAGE");
        for (String suffix : Set.of("compile", "preview", "test", "validate")) {
            put(out, "post", "/api/v1/scripts/" + suffix, "RULE_MANAGE");
        }
        put(out, "get", "/api/v1/script-sessions", "RULE_MANAGE");
        put(out, "post", "/api/v1/script-sessions", "RULE_MANAGE");
        put(out, "get", "/api/v1/script-sessions/{id}", "RULE_MANAGE");
        put(out, "delete", "/api/v1/script-sessions/{id}", "RULE_MANAGE");
        put(out, "get", "/api/v1/script-sessions/{id}/events", "RULE_MANAGE");
        for (String suffix : Set.of("apply", "promote", "validate")) {
            put(out, "post", "/api/v1/script-sessions/{id}/" + suffix, "RULE_MANAGE");
        }
        put(out, "post", "/api/v1/automation-sessions", "RULE_MANAGE");
        put(out, "post", "/api/v1/automation-sessions/{id}/trial", "RULE_MANAGE");
        put(out, "post", "/api/v1/automation-sessions/{id}/promote", "RULE_MANAGE");
        put(out, "post", "/api/v1/automation-sessions/{id}/revert",
                "CONDITIONAL_CREATED_RESOURCE{script-session=RULE_MANAGE}");
        put(out, "post", "/api/v1/targets/call-sites", "RULE_MANAGE");
        put(out, "post", "/api/v1/targets/resolve", "RULE_MANAGE");

        // Administration and identity management.
        put(out, "post", "/api/v1/control/schedulers/run-once", "ADMIN");
        put(out, "get", "/api/v1/auth/users", "USER_MANAGE");
        put(out, "delete", "/api/v1/auth/users/{username}", "USER_MANAGE");
        put(out, "post", "/api/v1/auth/users/{username}/token/replace", "USER_MANAGE");
        put(out, "post", "/api/v1/auth/users/{username}/tokens/renew", "USER_MANAGE");
        put(out, "get", "/api/v1/auth/tokens", "ADMIN");
        put(out, "post", "/api/v1/auth/tokens", "ADMIN");
        put(out, "delete", "/api/v1/auth/tokens/{id}", "ADMIN");
        put(out, "post", "/api/v1/auth/tokens/{id}/renew", "ADMIN");
        put(out, "get", "/api/v1/query/{resource}",
                "CONDITIONAL_RESOURCE{tokens=USER_MANAGE;default=AUTHENTICATED}");
        put(out, "get", "/api/v1/details/{resource}/{id}",
                "CONDITIONAL_RESOURCE{tokens=USER_MANAGE;default=AUTHENTICATED}");

        return Map.copyOf(out);
    }

    private static void put(Map<Route, String> out, String method, String path, String expression) {
        Route route = new Route(method, path);
        String previous = out.put(route, requireText(expression, "expression"));
        if (previous != null) {
            throw new IllegalStateException("Duplicate API authorization route: " + route);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
