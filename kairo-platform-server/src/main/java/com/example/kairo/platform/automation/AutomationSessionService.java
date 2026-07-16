package com.example.kairo.platform.automation;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ProxyType;
import com.example.kairo.api.SupportLevel;
import com.example.kairo.api.automation.AutomationSession;
import com.example.kairo.api.automation.AutomationSessionResource;
import com.example.kairo.api.automation.AutomationSessionStatus;
import com.example.kairo.api.automation.EnhancementCandidate;
import com.example.kairo.api.automation.EnhancementContextBundle;
import com.example.kairo.api.automation.ScriptApiSurface;
import com.example.kairo.api.operation.OperationType;
import com.example.kairo.api.write.ImpactSummary;
import com.example.kairo.api.write.PreviewResult;
import com.example.kairo.api.write.RiskLevel;
import com.example.kairo.platform.operation.OperationService;
import com.example.kairo.platform.persistence.mapper.AutomationSessionMapper;
import com.example.kairo.platform.script.ScriptCapabilityPolicyService;
import com.example.kairo.platform.script.ScriptSessionService;
import com.example.kairo.platform.service.EnhancementTargetResolutionService;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import com.example.kairo.platform.service.ScriptWorkbenchService;
import com.example.kairo.platform.service.TargetDiscoveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AI/automation session orchestrator (V1.6 &sect;4). A session is the top-level
 * boundary for AI-driven enhancement: it narrows the token's capability, tracks
 * every resource it creates, and provides one-click revert plus TTL cleanup.
 *
 * <p>Task steps (resolve-targets / validate-script / preview / trial / promote /
 * revert) delegate to the existing target-discovery, script-session, policy and
 * resolution services, but every step is recorded as a unified {@link
 * com.example.kairo.api.operation.Operation} and the created resources are
 * registered for revert.
 */
@Service
public final class AutomationSessionService {

    /** Request body for {@code POST /automation-sessions}. */
    public record CreateRequest(
            String caller,
            String source,
            String applicationId,
            String environmentId,
            String instanceId,
            String agentId,
            CapabilityProfile requestedCapabilityProfile,
            Long ttlMillis
    ) {
        public CreateRequest {
            caller = requireText(caller, "caller");
            source = requireText(source, "source");
            applicationId = requireText(applicationId, "applicationId");
            requestedCapabilityProfile = requestedCapabilityProfile == null
                    ? CapabilityProfile.SAFE : requestedCapabilityProfile;
            if (ttlMillis == null || ttlMillis <= 0) {
                ttlMillis = 600_000L; // default 10 min
            }
        }
    }

    /** Request body for {@code resolve-targets}. */
    public record ResolveTargetsRequest(String query, String environmentId) {
        public ResolveTargetsRequest {
            query = query == null ? "" : query.trim();
        }
    }

    /** Request body for {@code validate-script}. */
    public record ValidateScriptRequest(String script) {
        public ValidateScriptRequest {
            script = requireText(script, "script");
        }
    }

    /** Request body for {@code preview}. */
    public record PreviewRequest(Map<String, Object> target) {
        public PreviewRequest {
            Objects.requireNonNull(target, "target");
        }
    }

    /** Request body for {@code trial}. */
    public record TrialRequest(Map<String, Object> target, String script,
                               CapabilityProfile capabilityProfile, Long ttlMillis, Long maxHits) {
        public TrialRequest {
            Objects.requireNonNull(target, "target");
            script = requireText(script, "script");
            capabilityProfile = capabilityProfile == null ? CapabilityProfile.SAFE : capabilityProfile;
            ttlMillis = ttlMillis == null || ttlMillis <= 0 ? 300_000L : ttlMillis;
            maxHits = maxHits == null || maxHits <= 0 ? 1000L : maxHits;
        }
    }

    static final long DEFAULT_TTL_MILLIS = 600_000L;
    static final long MAX_TTL_MILLIS = 3_600_000L;

    private final AutomationSessionMapper sessionMapper;
    private final OperationService operationService;
    private final ScriptSessionService scriptSessionService;
    private final ScriptCapabilityPolicyService policyService;
    private final TargetDiscoveryService targetDiscoveryService;
    private final EnhancementTargetResolutionService resolutionService;
    private final ScriptWorkbenchService scriptWorkbenchService;
    private final RbacService rbacService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public AutomationSessionService(AutomationSessionMapper sessionMapper,
                                    OperationService operationService,
                                    ScriptSessionService scriptSessionService,
                                    ScriptCapabilityPolicyService policyService,
                                    TargetDiscoveryService targetDiscoveryService,
                                    EnhancementTargetResolutionService resolutionService,
                                    ScriptWorkbenchService scriptWorkbenchService,
                                    RbacService rbacService,
                                    TransactionTemplate transactionTemplate) {
        this(sessionMapper, operationService, scriptSessionService, policyService,
                targetDiscoveryService, resolutionService, scriptWorkbenchService, rbacService,
                Clock.systemUTC(), transactionTemplate);
    }

    AutomationSessionService(AutomationSessionMapper sessionMapper,
                             OperationService operationService,
                             ScriptSessionService scriptSessionService,
                             ScriptCapabilityPolicyService policyService,
                             TargetDiscoveryService targetDiscoveryService,
                             EnhancementTargetResolutionService resolutionService,
                             ScriptWorkbenchService scriptWorkbenchService,
                             RbacService rbacService, Clock clock,
                             TransactionTemplate transactionTemplate) {
        this.sessionMapper = sessionMapper;
        this.operationService = operationService;
        this.scriptSessionService = scriptSessionService;
        this.policyService = policyService;
        this.targetDiscoveryService = targetDiscoveryService;
        this.resolutionService = resolutionService;
        this.scriptWorkbenchService = scriptWorkbenchService;
        this.rbacService = rbacService;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    /** Create a session. The capability profile is narrowed to the effective tier. */
    public AutomationSession create(RequestContext context, CreateRequest request) {
        rbacService.require(context, "RULE_MANAGE");
        CapabilityProfile effective = policyService.effective(
                request.applicationId(), request.requestedCapabilityProfile());
        // A session may never widen beyond the effective tier; SAFE is the floor for AI use.
        CapabilityProfile maxProfile = effective;
        long ttl = Math.min(request.ttlMillis(), MAX_TTL_MILLIS);
        Instant now = clock.instant();
        long deadline = now.toEpochMilli() + ttl;
        String id = "auto-" + UUID.randomUUID();
        String tokenId = context.tokenId();
        Integer maxSessions = context.tokenScope() == null ? null : context.tokenScope().maxSessions();
        // V1.6 acceptance safety: the per-token count-then-insert is serialized by row-locking the
        // token row (SELECT ... FOR UPDATE) inside a programmatic transaction, so two concurrent
        // creates for the same token cannot both pass the limit. The class is final, so a CGLIB
        // @Transactional proxy is unavailable; a TransactionTemplate wraps the critical section
        // instead. Owner failure (an exception) rolls back the insert with the transaction.
        if (maxSessions != null && tokenId != null) {
            Integer limit = maxSessions;
            transactionTemplate.executeWithoutResult(status -> {
                sessionMapper.lockToken(tokenId);
                int active = sessionMapper.countActiveByToken(tokenId);
                if (active >= limit) {
                    throw PlatformException.conflict("AUTOMATION_SESSION_LIMIT_EXCEEDED",
                            "已达到该 Token 的并发会话上限：" + limit,
                            Map.of("active", active, "maxSessions", limit));
                }
                insertSessionRow(id, request, maxProfile, ttl, deadline, now, tokenId, context);
            });
        } else {
            insertSessionRow(id, request, maxProfile, ttl, deadline, now, tokenId, context);
        }
        operationService.recordEvent(id, "SESSION_CREATED", context.actor(),
                Map.of("source", request.source(), "maxCapabilityProfile", maxProfile.name()));
        return get(context, id);
    }

    private void insertSessionRow(String id, CreateRequest request, CapabilityProfile maxProfile,
                                  long ttl, long deadline, Instant now, String tokenId,
                                  RequestContext context) {
        sessionMapper.insertSession(id, request.caller(), request.source(),
                request.applicationId(), request.environmentId(), request.instanceId(),
                request.agentId(), maxProfile.name(), ttl, deadline,
                AutomationSessionStatus.CREATED.name(), RiskLevel.LOW.name(),
                context.correlationId(), tokenId,
                Timestamp.from(now), Timestamp.from(now));
    }

    public AutomationSession get(RequestContext context, String id) {
        return toSession(requireSession(id));
    }

    public List<AutomationSession> list(RequestContext context, String status) {
        String normalized = status == null || status.isBlank() ? null : status.toUpperCase();
        return sessionMapper.listByStatus(normalized).stream()
                .map(AutomationSessionService::toSession)
                .toList();
    }

    /** Resolve candidate targets and return the compact AI context bundle (§4.3). */
    public EnhancementContextBundle resolveTargets(RequestContext context, String id,
                                                   ResolveTargetsRequest request) {
        Map<String, Object> row = requireSession(id);
        ensureActive(row);
        String appId = str(row.get("application_id"));
        String envId = firstNonBlank(request.environmentId(), str(row.get("environment_id")));
        if (envId.isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD",
                    "resolve-targets 需要 environmentId（请求体或会话作用域）");
        }
        String opId = operationService.start(new OperationService.StartRequest(
                OperationType.AUTOMATION_TRIAL, "automation-session", id, RiskLevel.LOW,
                new ImpactSummary(List.of(new ImpactSummary.AffectedResource("automation-session", id)),
                        "app:" + appId, "single-instance", true, 1),
                context.actor(), context.correlationId(), null, id));
        operationService.running(opId);
        try {
            List<Map<String, Object>> matches = targetDiscoveryService.search(
                    context, request.query(), appId, envId);
            List<EnhancementCandidate> candidates = toCandidates(matches);
            EnhancementContextBundle bundle = buildBundle(id, str(row.get("max_capability_profile")),
                    candidates, envId);
            operationService.succeed(opId, Map.of("candidateCount", candidates.size()));
            return bundle;
        } catch (RuntimeException ex) {
            operationService.fail(opId, apiErrorFrom(ex));
            throw ex;
        }
    }

    /** Validate (compile) a script without applying it. Uses the local Groovy compiler
     *  so the AI can iterate fast without a live agent; returns structured diagnostics. */
    public Map<String, Object> validateScript(RequestContext context, String id, ValidateScriptRequest request) {
        Map<String, Object> row = requireSession(id);
        ensureActive(row);
        Map<String, Object> workbenchRequest = new LinkedHashMap<>();
        workbenchRequest.put("script", request.script());
        return scriptWorkbenchService.validate(workbenchRequest);
    }

    /** Preview the enhancement impact; returns a token the apply must echo (§2.3/§5.4). */
    public PreviewResult preview(RequestContext context, String id, PreviewRequest request) {
        Map<String, Object> row = requireSession(id);
        ensureActive(row);
        String appId = str(row.get("application_id"));
        String envId = firstNonBlank(str(row.get("environment_id")));
        if (envId.isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD", "preview 需要会话已绑定 environmentId");
        }
        Map<String, Object> resolved = resolutionService.resolve(context, appId, envId, request.target());
        long revision = clock.instant().toEpochMilli();
        String token = "prev-" + UUID.randomUUID();
        RiskLevel risk = riskFor(resolved);
        return new PreviewResult(token, revision, risk,
                new ImpactSummary(List.of(new ImpactSummary.AffectedResource("target",
                        str(resolved.get("targetId")))),
                        "app:" + appId, "single-instance", true, 1),
                Map.of("resolved", resolved), revision + 300_000L);
    }

    /** Create and apply a temporary trial script session within this automation session. */
    public Map<String, Object> trial(RequestContext context, String id, TrialRequest request) {
        Map<String, Object> row = requireSession(id);
        ensureActive(row);
        activate(row);
        Map<String, Object> sessionRequest = new LinkedHashMap<>();
        sessionRequest.put("agentId", firstNonBlank(str(row.get("agent_id"))));
        sessionRequest.put("applicationId", str(row.get("application_id")));
        sessionRequest.put("target", request.target());
        sessionRequest.put("script", request.script());
        // Narrow: trial may not exceed the session's max profile.
        CapabilityProfile sessionMax = CapabilityProfile.valueOf(str(row.get("max_capability_profile")));
        CapabilityProfile trialProfile = minProfile(sessionMax, request.capabilityProfile());
        sessionRequest.put("capabilityProfile", trialProfile.name());
        sessionRequest.put("ttlMillis", request.ttlMillis());
        sessionRequest.put("maxHits", request.maxHits());
        sessionRequest.put("requestedBy", context.actor());
        var created = scriptSessionService.create(context, sessionRequest);
        // create -> validate -> apply: the agent-side state machine requires VALIDATED before APPLY.
        scriptSessionService.validate(context, created.sessionId());
        var applied = scriptSessionService.apply(context, created.sessionId());
        registerResource(id, "script-session", created.sessionId(), true);
        operationService.start(new OperationService.StartRequest(
                OperationType.AUTOMATION_TRIAL, "script-session", created.sessionId(),
                RiskLevel.MEDIUM, null, context.actor(), context.correlationId(), null, id));
        return Map.of("sessionId", created.sessionId(), "status", applied.status().name(),
                "capabilityProfile", trialProfile.name());
    }

    /** Promote a trial script session to a formal rule version. */
    public Map<String, Object> promote(RequestContext context, String id, String scriptSessionId) {
        Map<String, Object> row = requireSession(id);
        ensureActive(row);
        var result = scriptSessionService.promote(context, scriptSessionId);
        registerResource(id, "rule-version", result.sessionId(), true);
        return Map.of("formalRuleId", result.sessionId(), "status", result.status().name());
    }

    /** One-click revert: roll back every reversible resource created in the session. */
    public AutomationSession revert(RequestContext context, String id) {
        Map<String, Object> row = requireSession(id);
        String status = str(row.get("status"));
        if (AutomationSessionStatus.REVERTED.name().equals(status)) {
            return get(context, id);
        }
        if (AutomationSessionStatus.EXPIRED.name().equals(status)
                || AutomationSessionStatus.FAILED.name().equals(status)) {
            throw PlatformException.conflict("AUTOMATION_SESSION_TERMINAL",
                    "会话已处于终态，无法撤销", Map.of("sessionId", id, "status", status));
        }
        List<Map<String, Object>> resources = sessionMapper.listResources(id);
        Map<String, Object> cleanup = new LinkedHashMap<>();
        List<String> reverted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Map<String, Object> resource : resources) {
            String type = str(resource.get("resource_type"));
            String resourceId = str(resource.get("resource_id"));
            boolean reversible = Boolean.TRUE.equals(resource.get("reversible"));
            if (!reversible) {
                continue;
            }
            try {
                revertResource(context, type, resourceId);
                reverted.add(type + ":" + resourceId);
            } catch (RuntimeException ex) {
                failed.add(type + ":" + resourceId + ":" + ex.getMessage());
            }
        }
        cleanup.put("reverted", reverted);
        cleanup.put("failed", failed);
        cleanup.put("revertedCount", reverted.size());
        long version = longVal(row.get("version"));
        int updated = sessionMapper.transition(id, AutomationSessionStatus.REVERTED.name(),
                RiskLevel.LOW.name(), PlatformJson.write(cleanup),
                Timestamp.from(clock.instant()), version);
        if (updated == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "会话版本已变化，无法撤销", Map.of("sessionId", id));
        }
        operationService.recordEvent(id, "SESSION_REVERTED", context.actor(), cleanup);
        return get(context, id);
    }

    /** Per-session event stream (session lifecycle + created-resource operations). */
    public List<Map<String, Object>> events(RequestContext context, String id) {
        requireSession(id);
        List<Map<String, Object>> events = new ArrayList<>();
        operationService.lifecycleEvents(id).forEach(e -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "lifecycle");
            ev.put("eventType", e.type());
            ev.put("occurredAt", e.occurredAt());
            events.add(ev);
        });
        for (var op : operationService.listBySession(id)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "operation");
            e.put("operationId", op.operationId());
            e.put("operationType", op.type().name());
            e.put("status", op.status().name());
            e.put("occurredAt", op.updatedAt());
            events.add(e);
        }
        return events;
    }

    /** Scheduled TTL cleanup (§4.1 "TTL 和绝对截止时间"). */
    public Map<String, Object> expireSessions() {
        long now = clock.instant().toEpochMilli();
        List<Map<String, Object>> expired = sessionMapper.listExpired(now);
        int reverted = 0;
        int failed = 0;
        for (Map<String, Object> row : expired) {
            String id = str(row.get("id"));
            try {
                List<Map<String, Object>> resources = sessionMapper.listResources(id);
                Map<String, Object> cleanup = new LinkedHashMap<>();
                List<String> revertedIds = new ArrayList<>();
                for (Map<String, Object> r : resources) {
                    if (Boolean.TRUE.equals(r.get("reversible"))) {
                        try {
                            revertResource(syntheticContext(str(row.get("caller"))),
                                    str(r.get("resource_type")), str(r.get("resource_id")));
                            revertedIds.add(str(r.get("resource_id")));
                        } catch (RuntimeException ignored) {
                            // best-effort: agent may be offline; session still expires
                        }
                    }
                }
                cleanup.put("reverted", revertedIds);
                long version = longVal(row.get("version"));
                int updated = sessionMapper.transition(id, AutomationSessionStatus.EXPIRED.name(),
                        RiskLevel.LOW.name(), PlatformJson.write(cleanup),
                        Timestamp.from(clock.instant()), version);
                if (updated > 0) {
                    reverted++;
                } else {
                    failed++;
                }
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        return Map.of("expired", expired.size(), "reverted", reverted, "failed", failed);
    }

    private void revertResource(RequestContext context, String type, String resourceId) {
        switch (type) {
            case "script-session" -> scriptSessionService.revert(context, resourceId);
            default -> {
                // rule-version unload and other types are reverted by their owning services;
                // script-session revert already cascades to the formal rule when promoted.
            }
        }
    }

    private void registerResource(String sessionId, String type, String resourceId, boolean reversible) {
        sessionMapper.insertResource("asr-" + UUID.randomUUID(), sessionId, type, resourceId,
                reversible, Timestamp.from(clock.instant()));
    }

    private void activate(Map<String, Object> row) {
        if (!AutomationSessionStatus.CREATED.name().equals(str(row.get("status")))) {
            return;
        }
        long version = longVal(row.get("version"));
        int updated = sessionMapper.transition(str(row.get("id")),
                AutomationSessionStatus.ACTIVE.name(), str(row.get("risk_level")),
                null, Timestamp.from(clock.instant()), version);
        if (updated == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "会话版本已变化", Map.of("sessionId", row.get("id")));
        }
    }

    private void ensureActive(Map<String, Object> row) {
        String status = str(row.get("status"));
        if (AutomationSessionStatus.EXPIRED.name().equals(status)
                || AutomationSessionStatus.REVERTED.name().equals(status)
                || AutomationSessionStatus.FAILED.name().equals(status)) {
            throw PlatformException.conflict("AUTOMATION_SESSION_TERMINAL",
                    "会话已处于终态，无法继续任务", Map.of("sessionId", row.get("id"), "status", status));
        }
    }

    private Map<String, Object> requireSession(String id) {
        Map<String, Object> row = sessionMapper.findById(id);
        if (row == null) {
            throw PlatformException.notFound("automation-session", id);
        }
        return row;
    }

    private EnhancementContextBundle buildBundle(String sessionId, String maxProfile,
                                                 List<EnhancementCandidate> candidates,
                                                 String envId) {
        List<EnhancementContextBundle.ClassLoaderSummary> loaders = candidates.stream()
                .map(c -> new EnhancementContextBundle.ClassLoaderSummary(
                        c.classLoaderId(), c.supportLevel(), c.proxyType(), ""))
                .distinct()
                .toList();
        List<EnhancementContextBundle.EnhancementLocationOption> locations = candidates.stream()
                .map(c -> new EnhancementContextBundle.EnhancementLocationOption(
                        c.targetId(),
                        List.of(com.example.kairo.api.EnhancementLocation.METHOD_ENTER,
                                com.example.kairo.api.EnhancementLocation.METHOD_RETURN,
                                com.example.kairo.api.EnhancementLocation.METHOD_THROW),
                        List.of()))
                .toList();
        ScriptApiSurface surface = scriptApiSurface(CapabilityProfile.valueOf(maxProfile));
        String json = PlatformJson.write(candidates);
        int sizeBytes = json.getBytes().length;
        // Enforce size cap by truncating candidates if necessary.
        List<EnhancementCandidate> capped = candidates;
        while (sizeBytes > EnhancementContextBundle.MAX_SIZE_BYTES && !capped.isEmpty()) {
            capped = capped.subList(0, capped.size() - 1);
            sizeBytes = PlatformJson.write(capped).getBytes().length;
        }
        return new EnhancementContextBundle(1, sessionId, capped, loaders, locations,
                List.of(), surface, sizeBytes, clock.instant().toEpochMilli());
    }

    private List<EnhancementCandidate> toCandidates(List<Map<String, Object>> matches) {
        List<EnhancementCandidate> out = new ArrayList<>();
        for (Map<String, Object> m : matches) {
            String className = str(m.get("className"));
            if (className.isBlank()) {
                className = str(m.get("class_name"));
            }
            String methodName = firstNonBlank(str(m.get("methodName")), str(m.get("method_name")));
            String descriptor = firstNonBlank(str(m.get("methodDescriptor")),
                    str(m.get("method_descriptor")), "()V");
            String classLoaderId = firstNonBlank(str(m.get("classLoaderId")),
                    str(m.get("class_loader_id")), "bootstrap");
            String targetId = firstNonBlank(str(m.get("targetId")), str(m.get("target_id")),
                    className + "#" + methodName);
            if (className.isBlank() || methodName.isBlank()) {
                continue;
            }
            double confidence = computeConfidence(methodName);
            out.add(new EnhancementCandidate(targetId, className, methodName, descriptor,
                    classLoaderId, confidence, confidence >= 0.8 ? "name-exact" : "signature-near",
                    SupportLevel.SUPPORTED, ProxyType.PLAIN));
        }
        return out;
    }

    private static double computeConfidence(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return 0.3;
        }
        // Heuristic: common data-access verbs rank higher as likely enhancement targets.
        return switch (methodName) {
            case "pay", "charge", "refund", "submit", "process", "handle", "execute", "invoke" -> 0.9;
            default -> 0.6;
        };
    }

    private static ScriptApiSurface scriptApiSurface(CapabilityProfile allowed) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "phase", Map.of("type", "string", "enum", List.of("BEFORE", "RETURN", "THROWS")),
                        "args", Map.of("type", "array"),
                        "result", Map.of("type", "object", "nullable", true),
                        "throwable", Map.of("type", "object", "nullable", true)),
                "required", List.of("phase", "args"));
        List<ScriptApiSurface.ScriptExample> examples = List.of(
                new ScriptApiSurface.ScriptExample("return-fixed",
                        "// BEFORE: short-circuit with a fixed value\nctx.result = 42\n",
                        "在方法进入前直接返回固定值 42"),
                new ScriptApiSurface.ScriptExample("record-and-continue",
                        "// RETURN: observe without mutating\nlog.info(\"returned {}\", ctx.result)\n",
                        "记录返回值但不改变结果"));
        Map<String, Object> diagnosticsFormat = Map.of(
                "shape", Map.of("phase", "string", "severity", "INFO|WARNING|ERROR",
                        "line", "int", "column", "int", "code", "string",
                        "message", "string", "suggestion", "string?"));
        Map<String, Object> limits = Map.of(
                "maxTtlMillis", 600_000L,
                "maxHits", 100_000L,
                "forbiddenApis", allowed == CapabilityProfile.SAFE
                        ? List.of("System.exit", "Runtime.exec", "reflective setAccessible")
                        : List.of());
        return new ScriptApiSurface(allowed, schema, examples, diagnosticsFormat, limits);
    }

    private static RiskLevel riskFor(Map<String, Object> resolved) {
        return RiskLevel.MEDIUM;
    }

    private static CapabilityProfile minProfile(CapabilityProfile a, CapabilityProfile b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private static com.example.kairo.api.error.ApiError apiErrorFrom(RuntimeException ex) {
        if (ex instanceof PlatformException pe) {
            return com.example.kairo.api.error.ApiError.of(pe.code(), pe.getMessage(),
                    pe.category(), pe.retryable());
        }
        // V1.7 M0: resolve INTERNAL_ERROR from the authoritative catalog (validates the code).
        com.example.kairo.api.error.KairoErrorCatalog.Entry internal =
                com.example.kairo.api.error.KairoErrorCatalog.require("INTERNAL_ERROR");
        return com.example.kairo.api.error.ApiError.of("INTERNAL_ERROR", ex.getMessage(),
                internal.category(), internal.retryable());
    }

    private static AutomationSession toSession(Map<String, Object> row) {
        String id = str(row.get("id"));
        List<AutomationSessionResource> resources = List.of();
        // resources loaded lazily by caller via get(); for list() we skip to keep it cheap.
        return new AutomationSession(
                id,
                str(row.get("caller")),
                str(row.get("source")),
                str(row.get("application_id")),
                nullableString(row.get("environment_id")),
                nullableString(row.get("instance_id")),
                nullableString(row.get("agent_id")),
                CapabilityProfile.valueOf(str(row.get("max_capability_profile"))),
                longVal(row.get("ttl_millis")),
                longVal(row.get("deadline_millis")),
                AutomationSessionStatus.valueOf(str(row.get("status"))),
                RiskLevel.valueOf(str(row.get("risk_level"))),
                resources,
                toMap(row.get("cleanup_result_json")),
                str(row.get("correlation_id")),
                longVal(row.get("version")),
                timestampMillis(row.get("created_at")),
                timestampMillis(row.get("updated_at")));
    }

    private static RequestContext syntheticContext(String actor) {
        return new RequestContext(actor == null ? "system" : actor, "", "", "automation-ttl", "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? Map.of() : PlatformJson.readMap(s);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nullableString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static long longVal(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return o == null ? 0L : Long.parseLong(String.valueOf(o));
    }

    private static long timestampMillis(Object o) {
        if (o instanceof Timestamp t) {
            return t.getTime();
        }
        if (o instanceof java.util.Date d) {
            return d.getTime();
        }
        return o == null ? 0L : Timestamp.valueOf(String.valueOf(o)).getTime();
    }
}
