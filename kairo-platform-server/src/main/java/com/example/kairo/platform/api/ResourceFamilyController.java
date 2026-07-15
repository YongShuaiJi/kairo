package com.example.kairo.platform.api;

import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformQueryService;
import com.example.kairo.platform.service.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * V1.6 &sect;3 resource-family read endpoints. Surfaces the previously
 * dashboard-embedded or generic-query resources as first-class, stable,
 * individually-addressable resources with camelCase JSON (database snake_case
 * is normalised by {@link CamelCaseKeys} and never leaks directly).
 *
 * <p>Every deprecated {@code /query/{resource}} family has a first-class
 * replacement here or in {@link PlatformController}; the deprecated paths are
 * retained only for backward compatibility (V1.6 &sect;3 gradual migration).
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class ResourceFamilyController {

    private static final int LIST_LIMIT = 200;

    private final PlatformCoreService coreService;
    private final PlatformQueryService queryService;
    private final RequestContextFactory requestContextFactory;

    public ResourceFamilyController(PlatformCoreService coreService,
                                    PlatformQueryService queryService,
                                    RequestContextFactory requestContextFactory) {
        this.coreService = coreService;
        this.queryService = queryService;
        this.requestContextFactory = requestContextFactory;
    }

    @GetMapping("/applications")
    public List<Map<String, Object>> applications(HttpServletRequest request) {
        context(request);
        return list("application");
    }

    @GetMapping("/environments")
    public List<Map<String, Object>> environments(HttpServletRequest request) {
        context(request);
        return list("environment");
    }

    /** V1.6 §3 /audit-events: the audit-event stream as its own resource. */
    @GetMapping("/audit-events")
    public List<Map<String, Object>> auditEvents(HttpServletRequest request) {
        context(request);
        return normalized(queryService.recentAudits());
    }

    /** V1.6 §3 /diagnostics: a lightweight machine-readable health probe. */
    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics(HttpServletRequest request) {
        context(request);
        return normalized(coreService.health());
    }

    /** V1.6 §3 first-class replacements for the remaining deprecated query families. */
    @GetMapping("/rollout-environments")
    public List<Map<String, Object>> rolloutEnvironments(HttpServletRequest request) {
        context(request);
        return queryItems("rollout-environments");
    }

    @GetMapping("/rollout-applications")
    public List<Map<String, Object>> rolloutApplications(HttpServletRequest request) {
        context(request);
        return queryItems("rollout-applications");
    }

    @GetMapping("/rollout-targets")
    public List<Map<String, Object>> rolloutTargets(HttpServletRequest request) {
        context(request);
        return queryItems("rollout-targets");
    }

    @GetMapping("/rollback-executions")
    public List<Map<String, Object>> rollbackExecutions(HttpServletRequest request) {
        context(request);
        return queryItems("rollback-executions");
    }

    @GetMapping("/attach-executors")
    public List<Map<String, Object>> attachExecutors(HttpServletRequest request) {
        context(request);
        return queryItems("attach-executors");
    }

    @GetMapping("/attach-targets")
    public List<Map<String, Object>> attachTargets(HttpServletRequest request) {
        context(request);
        return queryItems("attach-targets");
    }

    @GetMapping("/attach-executor-commands")
    public List<Map<String, Object>> attachExecutorCommands(HttpServletRequest request) {
        context(request);
        return queryItems("attach-executor-commands");
    }

    private RequestContext context(HttpServletRequest request) {
        return requestContextFactory.from(request);
    }

    /** coreService.list rows (lower-cased snake_case) normalised to camelCase. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String table) {
        return (List<Map<String, Object>>) CamelCaseKeys.normalize(coreService.list(table, "created_at, id"));
    }

    /** Reuse the deprecated query service's proven resource->table mapping, then camelCase. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryItems(String resource) {
        Map<String, Object> paged = queryService.page(resource, 0, LIST_LIMIT, "");
        Object items = paged.get("items");
        return items instanceof List<?> list
                ? (List<Map<String, Object>>) CamelCaseKeys.normalize(list)
                : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalized(Object value) {
        return (List<Map<String, Object>>) CamelCaseKeys.normalize(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalized(Map<String, Object> value) {
        return (Map<String, Object>) CamelCaseKeys.normalize(value);
    }
}
