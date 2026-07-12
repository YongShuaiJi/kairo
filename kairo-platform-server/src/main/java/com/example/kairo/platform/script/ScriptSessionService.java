package com.example.kairo.platform.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptCompilationResult;
import com.example.kairo.api.ScriptDiagnostic;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.api.ScriptSessionStatus;
import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.command.ScriptCommandFailure;
import com.example.kairo.platform.command.ScriptCommandTimeoutException;
import com.example.kairo.platform.command.ScriptSessionExchange;
import com.example.kairo.platform.persistence.mapper.ScriptSessionEventMapper;
import com.example.kairo.platform.persistence.mapper.ScriptSessionMapper;
import com.example.kairo.platform.service.BusinessIdService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform-side owner of the temporary {@code ScriptSession} lifecycle (§3.5).
 *
 * <p>Computes the effective tier, persists each session, dispatches one agent command per
 * transition, and reconciles the persisted status from each ack. Every state change is gated by an
 * optimistic version check and an idempotency key; the trial script source is carried in the
 * in-memory exchange only, so the durable {@code script_session} row and command payload store just
 * the script hash. Expiry compensation runs on a schedule: non-terminal sessions past their deadline
 * are marked EXPIRED and a best-effort revert is enqueued (the agent also expires locally and
 * independently, so this is defense in depth). Promotion never widens scope: the formal rule reuses
 * the session's exact profile, revision, target and script under the same id, dropping only the TTL
 * and hit cap, and the session becomes REVERTED.
 */
@Service
public class ScriptSessionService {

    public static final String CMD_CREATE = "SCRIPT_SESSION_CREATE";
    public static final String CMD_VALIDATE = "SCRIPT_SESSION_VALIDATE";
    public static final String CMD_APPLY = "SCRIPT_SESSION_APPLY";
    public static final String CMD_PROMOTE = "SCRIPT_SESSION_PROMOTE";
    public static final String CMD_REVERT = "SCRIPT_SESSION_REVERT";
    public static final String CMD_COMPILE = "SCRIPT_COMPILE";

    private final ScriptSessionMapper sessionMapper;
    private final ScriptSessionEventMapper eventMapper;
    private final ScriptCapabilityPolicyService policyService;
    private final AgentCommandService commands;
    private final ScriptSessionExchange exchange;
    private final PlatformCoreService events;
    private final BusinessIdService businessIdService;
    private final RbacService rbacService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${kairo.platform.script.command-timeout-ms:15000}")
    private long commandTimeoutMillis;

    @Value("${kairo.platform.script.max-ttl-millis:300000}")
    private long maxTtlMillis;

    @Value("${kairo.platform.script.max-hits:100}")
    private long maxHits;

    @Value("${kairo.platform.script.default-ttl-millis:60000}")
    private long defaultTtlMillis;

    @Value("${kairo.platform.script.default-max-hits:1}")
    private long defaultMaxHits;

    @Autowired
    public ScriptSessionService(ScriptSessionMapper sessionMapper,
                                ScriptSessionEventMapper eventMapper,
                                ScriptCapabilityPolicyService policyService,
                                AgentCommandService commands,
                                ScriptSessionExchange exchange,
                                PlatformCoreService events,
                                BusinessIdService businessIdService,
                                RbacService rbacService,
                                ObjectMapper objectMapper) {
        this(sessionMapper, eventMapper, policyService, commands, exchange, events,
                businessIdService, rbacService, objectMapper, Clock.systemUTC());
    }

    ScriptSessionService(ScriptSessionMapper sessionMapper,
                         ScriptSessionEventMapper eventMapper,
                         ScriptCapabilityPolicyService policyService,
                         AgentCommandService commands,
                         ScriptSessionExchange exchange,
                         PlatformCoreService events,
                         BusinessIdService businessIdService,
                         RbacService rbacService,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.policyService = policyService;
        this.commands = commands;
        this.exchange = exchange;
        this.events = events;
        this.businessIdService = businessIdService;
        this.rbacService = rbacService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    /** Create a trial session: validate, compute the effective tier, persist, dispatch and confirm. */
    public ScriptSessionResult create(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "RULE_MANAGE");
        String agentId = requiredString(request, "agentId");
        Map<String, Object> targetMap = requiredMap(request, "target");
        String className = requiredString(targetMap, "className");
        String classLoaderId = optionalString(targetMap, "classLoaderId", null);
        String methodName = requiredString(targetMap, "methodName");
        String methodDescriptor = requiredString(targetMap, "methodDescriptor");
        String script = requiredString(request, "script");
        CapabilityProfile requested = parseProfile(request.get("capabilityProfile"), CapabilityProfile.SAFE);
        long ttlMillis = optionalPositiveLong(request, "ttlMillis", defaultTtlMillis);
        long maxHits = optionalPositiveLong(request, "maxHits", defaultMaxHits);
        String requestedBy = optionalString(request, "requestedBy", context.actor());
        String sessionId = optionalString(request, "sessionId", null);
        String idempotencyKey = optionalString(request, "idempotencyKey", null);

        String applicationId = resolveApplication(agentId, optionalString(request, "applicationId", null));
        validateTtlAndHits(ttlMillis, maxHits);

        ScriptSessionRecord existing = idempotencyKey == null ? null
                : sessionMapper.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return toResult(existing);
        }
        if (sessionId == null) {
            sessionId = businessIdService.nextId("script_session", "Script Session");
        }
        if (idempotencyKey == null) {
            idempotencyKey = "script-session:" + sessionId;
        }
        if (sessionMapper.findById(sessionId) != null) {
            throw PlatformException.conflict("SCRIPT_SESSION_EXISTS",
                    "Script session already exists: " + sessionId, Map.of("sessionId", sessionId));
        }
        enforceSingleInstancePerTarget(agentId, className, classLoaderId);

        CapabilityProfile platformMax = policyService.platformMax();
        CapabilityProfile appMax = policyService.applicationMax(applicationId);
        CapabilityProfile effective = CapabilityProfile.effective(platformMax, appMax, requested);
        ScriptPolicyRevision revision = policyService.revisionToPin(applicationId);
        String scriptHash = PlatformJson.sha256(script);

        Instant now = clock.instant();
        Timestamp createdAt = Timestamp.from(now);
        Timestamp expiresAt = Timestamp.from(now.plusMillis(ttlMillis));
        ScriptSessionRecord record = new ScriptSessionRecord(
                sessionId, agentId, applicationId, className, classLoaderId, methodName, methodDescriptor,
                scriptHash, requested, effective, platformMax, appMax, revision.revision(), revision.hash(),
                ttlMillis, maxHits, ScriptSessionStatus.CREATED, 0L, 1L, idempotencyKey, requestedBy,
                null, "{}", "[]", createdAt, expiresAt, null, null, createdAt, context.actor(),
                context.correlationId());
        try {
            sessionMapper.insert(record);
        } catch (DuplicateKeyException duplicateKey) {
            ScriptSessionRecord concurrent = sessionMapper.findByIdempotencyKey(idempotencyKey);
            if (concurrent != null) {
                return toResult(concurrent);
            }
            throw PlatformException.conflict("SCRIPT_SESSION_EXISTS",
                    "Script session already exists: " + sessionId, Map.of("sessionId", sessionId));
        }
        recordEvent(sessionId, "script.session.create", null, ScriptSessionStatus.CREATED,
                requestedBy, "Created session profile=" + effective + " ttl=" + ttlMillis + "ms", null);

        Map<String, Object> payload = baseSpecPayload(record, effective, revision);
        payload.put("scriptHash", scriptHash);
        AgentAck ack = dispatch(context, agentId, CMD_CREATE, payload, script, idempotencyKey + ":create");
        if (ack.failed()) {
            transitionFailed(context, record, ack, "Agent rejected session creation");
        } else {
            // The agent confirms creation; the platform session stays CREATED with the agent result recorded.
            recordAgentResult(record, ack);
            // §2.1: audit the requested tier, the final (effective) tier and the decision source.
            events.recordEvent(context, "script_session.create", "script_session", sessionId,
                    record.version(), null, Map.of(
                            "status", ScriptSessionStatus.CREATED.name(),
                            "requestedProfile", requested.name(),
                            "effectiveProfile", effective.name(),
                            "platformMaxProfile", platformMax.name(),
                            "applicationMaxProfile", appMax.name()),
                    "SUCCESS", "Created trial session",
                    Map.of("agentId", agentId, "applicationId", applicationId,
                            "decisionSource", "min(platform, application, requested)",
                            "idempotencyKey", idempotencyKey));
        }
        return toResult(requireSession(sessionId));
    }

    // ------------------------------------------------------------------ validate

    /** Dry-run compile on the agent; CREATED -> VALIDATED or FAILED. */
    public ScriptSessionResult validate(RequestContext context, String sessionId) {
        rbacService.require(context, "RULE_MANAGE");
        ScriptSessionRecord session = requireSession(sessionId);
        requireState(session, ScriptSessionStatus.CREATED, "validate");
        Map<String, Object> payload = commandPayload(CMD_VALIDATE, session);
        AgentAck ack = dispatch(context, session.agentId(), CMD_VALIDATE, payload, null,
                session.idempotencyKey() + ":validate");
        if (ack.failed()) {
            transitionFailed(context, session, ack, "Script validation failed");
        } else {
            transition(context, session, ScriptSessionStatus.VALIDATED, ack, null, null, null,
                    "script.session.validate", "Validated script");
        }
        return toResult(requireSession(sessionId));
    }

    // ------------------------------------------------------------------ apply

    /** Publish the bounded trial rule on the agent; VALIDATED -> APPLIED or FAILED. */
    public ScriptSessionResult apply(RequestContext context, String sessionId) {
        rbacService.require(context, "RULE_MANAGE");
        ScriptSessionRecord session = requireSession(sessionId);
        requireState(session, ScriptSessionStatus.VALIDATED, "apply");
        Map<String, Object> payload = commandPayload(CMD_APPLY, session);
        AgentAck ack = dispatch(context, session.agentId(), CMD_APPLY, payload, null,
                session.idempotencyKey() + ":apply");
        if (ack.failed()) {
            transitionFailed(context, session, ack, "Script apply failed");
        } else {
            Timestamp now = Timestamp.from(clock.instant());
            transition(context, session, ScriptSessionStatus.APPLIED, ack, null, now, null,
                    "script.session.apply", "Applied trial rule");
        }
        return toResult(requireSession(sessionId));
    }

    // ------------------------------------------------------------------ promote

    /** Promote to a formal rule under the same id; VALIDATED|APPLIED -> REVERTED or FAILED. */
    public ScriptSessionResult promote(RequestContext context, String sessionId) {
        rbacService.require(context, "RULE_MANAGE");
        ScriptSessionRecord session = requireSession(sessionId);
        ScriptSessionStatus current = session.status();
        if (current != ScriptSessionStatus.VALIDATED && current != ScriptSessionStatus.APPLIED) {
            throw PlatformException.conflict("SCRIPT_SESSION_INVALID_TRANSITION",
                    "Cannot promote session in state " + current,
                    Map.of("sessionId", sessionId, "status", current.name()));
        }
        Map<String, Object> payload = commandPayload(CMD_PROMOTE, session);
        AgentAck ack = dispatch(context, session.agentId(), CMD_PROMOTE, payload, null,
                session.idempotencyKey() + ":promote");
        if (ack.failed()) {
            transitionFailed(context, session, ack, "Script promote failed");
        } else {
            Timestamp now = Timestamp.from(clock.instant());
            transition(context, session, ScriptSessionStatus.REVERTED, ack, session.id(), null, now,
                    "script.session.promote", "Promoted to formal rule profile=" + session.effectiveProfile());
        }
        return toResult(requireSession(sessionId));
    }

    // ------------------------------------------------------------------ revert (DELETE)

    /** Revert a session; idempotent for terminal sessions. Non-terminal sessions dispatch a revert. */
    public ScriptSessionResult revert(RequestContext context, String sessionId) {
        rbacService.require(context, "RULE_MANAGE");
        ScriptSessionRecord session = requireSession(sessionId);
        if (session.status().terminal()) {
            return toResult(session);
        }
        Map<String, Object> payload = commandPayload(CMD_REVERT, session);
        AgentAck ack = dispatch(context, session.agentId(), CMD_REVERT, payload, null,
                session.idempotencyKey() + ":revert:" + session.version());
        if (ack.failed()) {
            transitionFailed(context, session, ack, "Script revert failed");
        } else {
            Timestamp now = Timestamp.from(clock.instant());
            transition(context, session, ScriptSessionStatus.REVERTED, ack, session.formalRuleId(), null, now,
                    "script.session.revert", "Reverted session");
        }
        return toResult(requireSession(sessionId));
    }

    // ------------------------------------------------------------------ queries

    public ScriptSessionResult describe(RequestContext context, String sessionId) {
        rbacService.require(context, "RULE_MANAGE");
        return toResult(requireSession(sessionId));
    }

    public List<ScriptSessionResult> listByApplication(RequestContext context, String applicationId) {
        rbacService.require(context, "RULE_MANAGE");
        requireText(applicationId, "applicationId");
        return sessionMapper.listByApplication(applicationId).stream().map(this::toResult).toList();
    }

    /**
     * Full record view for the Web console: the frozen {@link ScriptSessionResult} DTO carries only
     * the lifecycle snapshot, but the page must show the tier, target, TTL and policy revision
     * (§3.6). Returns the persisted row as a map so the page can render every field without a DTO
     * change; diagnostics are parsed back into structured objects for the client.
     */
    public Map<String, Object> detail(RequestContext context, String sessionId) {
        rbacService.require(context, "RULE_MANAGE");
        return toDetailMap(requireSession(sessionId));
    }

    /**
     * List sessions for the console, optionally filtered by application. Returns full record maps
     * (not the lifecycle DTO) so the page can show tier, target and TTL alongside status.
     */
    public List<Map<String, Object>> list(RequestContext context, String applicationId) {
        rbacService.require(context, "RULE_MANAGE");
        List<ScriptSessionRecord> rows = (applicationId == null || applicationId.isBlank())
                ? sessionMapper.listAll()
                : sessionMapper.listByApplication(applicationId);
        return rows.stream().map(this::toDetailMap).toList();
    }

    public List<ScriptSessionEvent> history(RequestContext context, String sessionId) {
        rbacService.require(context, "RULE_MANAGE");
        requireSession(sessionId);
        return eventMapper.listBySession(sessionId);
    }

    // ------------------------------------------------------------------ compile

    /** Compile a script against an agent's target ClassLoader; dispatches SCRIPT_COMPILE. */
    public ScriptCompilationResult compile(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "RULE_MANAGE");
        String agentId = requiredString(request, "agentId");
        String script = requiredString(request, "script");
        String targetClassLoaderId = requiredString(request, "targetClassLoaderId");
        CapabilityProfile requested = parseProfile(request.get("capabilityProfile"), CapabilityProfile.SAFE);
        String applicationId = resolveApplication(agentId, optionalString(request, "applicationId", null));
        CapabilityProfile effective = policyService.effective(applicationId, requested);
        ScriptPolicyRevision revision = policyService.revisionToPin(applicationId);
        String scriptHash = PlatformJson.sha256(script);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", CMD_COMPILE);
        payload.put("agentId", agentId);
        payload.put("scriptHash", scriptHash);
        payload.put("capabilityProfile", effective.name());
        payload.put("policyRevision", Map.of("revision", revision.revision(), "hash", revision.hash()));
        payload.put("targetClassLoaderId", targetClassLoaderId);
        AgentAck ack = dispatch(context, agentId, CMD_COMPILE, payload, script,
                "script-compile:" + agentId + ":" + UUID.randomUUID());
        if (ack.failed()) {
            List<ScriptDiagnostic> diagnostics = parseDiagnostics(ackDiagnostics(ack));
            if (diagnostics.isEmpty()) {
                diagnostics = List.of(new ScriptDiagnostic(ScriptDiagnostic.Phase.COMPILATION,
                        ScriptDiagnostic.Severity.ERROR, 0, 0, "SCRIPT_COMPILE_ERROR",
                        ack.errorMessage() == null ? "Script compile failed" : ack.errorMessage(),
                        targetClassLoaderId, "See agent logs for details."));
            }
            return new ScriptCompilationResult(false, scriptHash, effective, revision,
                    "unknown", targetClassLoaderId, diagnostics);
        }
        Map<String, Object> result = ack.result();
        boolean successful = Boolean.TRUE.equals(result.get("successful"));
        List<ScriptDiagnostic> diagnostics = parseDiagnostics(result.get("diagnostics"));
        return new ScriptCompilationResult(successful, scriptHash, effective, revision,
                stringOr(result.get("compilerVersion"), "unknown"), targetClassLoaderId, diagnostics);
    }

    // ------------------------------------------------------------------ expiry compensation

    /**
     * Sweep: mark non-terminal sessions past their deadline EXPIRED and enqueue a best-effort revert.
     *
     * <p>Intentionally not transactional: each session is handled independently and the best-effort
     * revert is dispatched in its own transaction. A missing agent makes {@code enqueue} throw, which
     * is caught and swallowed &mdash; had this whole sweep run in one transaction, that throw would
     * mark it rollback-only despite the catch, so each statement commits independently instead.
     */
    public Map<String, Object> expireSessions() {
        Timestamp now = Timestamp.from(clock.instant());
        List<ScriptSessionRecord> expirable = sessionMapper.findExpirable(now);
        int expired = 0;
        int reverted = 0;
        for (ScriptSessionRecord session : expirable) {
            int updated = sessionMapper.transition(session.id(), ScriptSessionStatus.EXPIRED,
                    session.hitCount(), session.agentResultJson(), session.diagnosticsJson(),
                    session.formalRuleId(), session.appliedAt(), now, session.version(), now);
            if (updated == 0) {
                continue;
            }
            expired++;
            recordEvent(session.id(), "script.session.expire", session.status().name(),
                    ScriptSessionStatus.EXPIRED, "ttl-cleanup",
                    session.status() == ScriptSessionStatus.APPLIED
                            ? "Session expired (trial rule reverted by agent TTL)"
                            : "Session expired (TTL elapsed)",
                    null);
            events.recordEvent(systemContext(), "script_session.expire", "script_session",
                    session.id(), session.version(), null, Map.of("status", "EXPIRED"), "SUCCESS",
                    "Session expired by platform sweep",
                    Map.of("agentId", session.agentId(), "applicationId", session.applicationId()));
            if (session.status() == ScriptSessionStatus.APPLIED) {
                reverted += enqueueBestEffortRevert(session);
            }
        }
        return Map.of("expired", expired, "revertsEnqueued", reverted);
    }

    private int enqueueBestEffortRevert(ScriptSessionRecord session) {
        try {
            Map<String, Object> payload = commandPayload(CMD_REVERT, session);
            commands.enqueue(systemContext(), session.agentId(), CMD_REVERT, payload,
                    session.idempotencyKey() + ":expire-revert", 1, clock.instant());
            return 1;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ dispatch + transitions

    private AgentAck dispatch(RequestContext context, String agentId, String commandType,
                              Map<String, Object> payload, String scriptSource, String idempotencyKey) {
        Map<String, Object> command;
        try {
            command = commands.createScriptCommand(context, agentId, commandType, payload, scriptSource,
                    idempotencyKey);
        } catch (PlatformException e) {
            throw e;
        } catch (RuntimeException e) {
            throw PlatformException.conflict("SCRIPT_COMMAND_DISPATCH_FAILED",
                    "Failed to dispatch script command: " + e.getMessage(),
                    Map.of("commandType", commandType, "agentId", agentId));
        }
        String commandId = String.valueOf(command.get("id"));
        try {
            Map<String, Object> result = exchange.await(commandId, Duration.ofMillis(commandTimeoutMillis));
            return AgentAck.success(result);
        } catch (ScriptCommandFailure failure) {
            return AgentAck.failed(failure.getMessage(), failure.result());
        } catch (ScriptCommandTimeoutException timeout) {
            return AgentAck.timeout(timeout.getMessage());
        } catch (RuntimeException unexpected) {
            return AgentAck.timeout("Agent did not acknowledge " + commandType
                    + ": " + unexpected.getMessage());
        }
    }

    /** Record an agent ack without a status change (used by create, which stays CREATED). */
    private void recordAgentResult(ScriptSessionRecord session, AgentAck ack) {
        Timestamp now = Timestamp.from(clock.instant());
        sessionMapper.applyAgentResult(session.id(), hitCount(ack, session), agentResultJson(ack),
                diagnosticsJson(ack), session.version(), now);
    }

    /** Optimistic status transition carrying the agent ack's result; bumps version and audits. */
    private ScriptSessionRecord transition(RequestContext context, ScriptSessionRecord session,
                                            ScriptSessionStatus to, AgentAck ack, String formalRuleId,
                                            Timestamp appliedAt, Timestamp revertedAt,
                                            String action, String detail) {
        Timestamp now = Timestamp.from(clock.instant());
        int updated = sessionMapper.transition(session.id(), to, hitCount(ack, session),
                agentResultJson(ack), diagnosticsJson(ack), formalRuleId, appliedAt, revertedAt,
                session.version(), now);
        if (updated == 0) {
            ScriptSessionRecord current = sessionMapper.findById(session.id());
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "Script session was modified concurrently; reload and retry",
                    Map.of("sessionId", session.id(), "expectedVersion", session.version(),
                            "status", current == null ? "UNKNOWN" : current.status().name()));
        }
        recordEvent(session.id(), action, session.status().name(), to,
                context.actor(), detail, null);
        ScriptSessionRecord reloaded = sessionMapper.findById(session.id());
        events.recordEvent(context, action, "script_session", session.id(),
                reloaded.version(), session, reloaded, "SUCCESS", detail,
                Map.of("agentId", session.agentId(), "applicationId", session.applicationId(),
                        "status", to.name()));
        return reloaded;
    }

    private void transitionFailed(RequestContext context, ScriptSessionRecord session, AgentAck ack,
                                  String reason) {
        List<ScriptDiagnostic> diagnostics = parseDiagnostics(ackDiagnostics(ack));
        if (diagnostics.isEmpty()) {
            diagnostics = List.of(new ScriptDiagnostic(ScriptDiagnostic.Phase.EXECUTION,
                    ScriptDiagnostic.Severity.ERROR, 0, 0, "SCRIPT_COMMAND_FAILED",
                    ack.errorMessage() == null ? reason : ack.errorMessage(),
                    session.targetClassLoaderId(), "Create a new session to retry; a failed session is terminal."));
        }
        Timestamp now = Timestamp.from(clock.instant());
        String diagnosticsJson = writeDiagnostics(diagnostics);
        int updated = sessionMapper.transition(session.id(), ScriptSessionStatus.FAILED, session.hitCount(),
                agentResultJson(ack), diagnosticsJson, session.formalRuleId(), session.appliedAt(), now,
                session.version(), now);
        if (updated == 0) {
            return;
        }
        recordEvent(session.id(), "script.session.failed", session.status().name(),
                ScriptSessionStatus.FAILED, context.actor(), reason, null);
        events.recordEvent(context, "script_session.failed", "script_session", session.id(),
                session.version() + 1, session, null, "FAILED", reason,
                Map.of("agentId", session.agentId(), "applicationId", session.applicationId()));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> baseSpecPayload(ScriptSessionRecord record, CapabilityProfile effective,
                                                 ScriptPolicyRevision revision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", CMD_CREATE);
        payload.put("sessionId", record.id());
        payload.put("agentId", record.agentId());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("className", record.targetClassName());
        target.put("classLoaderId", record.targetClassLoaderId() == null ? "" : record.targetClassLoaderId());
        target.put("methodName", record.targetMethodName());
        target.put("methodDescriptor", record.targetMethodDescriptor());
        payload.put("target", target);
        payload.put("capabilityProfile", effective.name());
        payload.put("policyRevision", Map.of("revision", revision.revision(), "hash", revision.hash()));
        payload.put("ttlMillis", record.ttlMillis());
        payload.put("maxHits", record.maxHits());
        payload.put("requestedBy", record.requestedBy());
        return payload;
    }

    private Map<String, Object> commandPayload(String commandType, ScriptSessionRecord session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", commandType);
        payload.put("sessionId", session.id());
        payload.put("agentId", session.agentId());
        return payload;
    }

    private String resolveApplication(String agentId, String requested) {
        String agentApp = sessionMapper.findAgentApplication(agentId);
        if (agentApp == null) {
            throw PlatformException.notFound("agent_instance", agentId);
        }
        if (requested != null && !requested.isBlank() && !requested.equals(agentApp)) {
            throw PlatformException.badRequest("INVALID_FIELD",
                    "Agent " + agentId + " does not belong to application " + requested);
        }
        return agentApp;
    }

    private void enforceSingleInstancePerTarget(String agentId, String className, String classLoaderId) {
        Timestamp now = Timestamp.from(clock.instant());
        int active = sessionMapper.countActiveByTarget(agentId, className, classLoaderId, now);
        if (active > 0) {
            throw PlatformException.conflict("SCRIPT_SESSION_TARGET_BUSY",
                    "Target already has an active trial session",
                    Map.of("agentId", agentId, "className", className));
        }
    }

    private void validateTtlAndHits(long ttlMillis, long maxHits) {
        if (ttlMillis > this.maxTtlMillis) {
            throw PlatformException.badRequest("INVALID_TTL",
                    "ttlMillis " + ttlMillis + " exceeds platform limit " + this.maxTtlMillis);
        }
        if (maxHits > this.maxHits) {
            throw PlatformException.badRequest("INVALID_MAX_HITS",
                    "maxHits " + maxHits + " exceeds platform limit " + this.maxHits);
        }
    }

    private void requireState(ScriptSessionRecord session, ScriptSessionStatus expected, String action) {
        if (session.status() != expected) {
            throw PlatformException.conflict("SCRIPT_SESSION_INVALID_TRANSITION",
                    "Cannot " + action + " session in state " + session.status()
                            + "; expected " + expected,
                    Map.of("sessionId", session.id(), "status", session.status().name(),
                            "expected", expected.name()));
        }
    }

    private ScriptSessionRecord requireSession(String sessionId) {
        ScriptSessionRecord session = sessionMapper.findById(requireText(sessionId, "sessionId"));
        if (session == null) {
            throw PlatformException.notFound("script_session", sessionId);
        }
        return session;
    }

    private void recordEvent(String sessionId, String action, String fromStatus,
                             ScriptSessionStatus toStatus, String actor, String detail, String commandId) {
        Timestamp now = Timestamp.from(clock.instant());
        eventMapper.insert(new ScriptSessionEvent(
                "script-session-event-" + UUID.randomUUID(), sessionId, action, fromStatus,
                toStatus.name(), actor, truncate(detail, 1024), commandId, now));
    }

    private ScriptSessionResult toResult(ScriptSessionRecord record) {
        return new ScriptSessionResult(record.id(), record.status(),
                record.createdAt().getTime(), record.expiresAt().getTime(),
                record.hitCount(), parseDiagnostics(record.diagnosticsJson()));
    }

    /** Full record as a map for the Web console (tier, target, TTL, policy revision, diagnostics). */
    private Map<String, Object> toDetailMap(ScriptSessionRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", record.id());
        map.put("agentId", record.agentId());
        map.put("applicationId", record.applicationId());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("className", record.targetClassName());
        target.put("classLoaderId", record.targetClassLoaderId());
        target.put("methodName", record.targetMethodName());
        target.put("methodDescriptor", record.targetMethodDescriptor());
        map.put("target", target);
        map.put("scriptHash", record.scriptHash());
        map.put("requestedProfile", record.requestedProfile().name());
        map.put("effectiveProfile", record.effectiveProfile().name());
        map.put("platformMaxProfile", record.platformMaxProfile().name());
        map.put("applicationMaxProfile", record.applicationMaxProfile().name());
        map.put("policyRevision", Map.of("revision", record.policyRevision(), "hash", record.policyHash()));
        map.put("ttlMillis", record.ttlMillis());
        map.put("maxHits", record.maxHits());
        map.put("status", record.status().name());
        map.put("hitCount", record.hitCount());
        map.put("version", record.version());
        map.put("requestedBy", record.requestedBy());
        map.put("formalRuleId", record.formalRuleId());
        map.put("createdAt", record.createdAt().getTime());
        map.put("expiresAt", record.expiresAt().getTime());
        map.put("appliedAt", record.appliedAt() == null ? null : record.appliedAt().getTime());
        map.put("revertedAt", record.revertedAt() == null ? null : record.revertedAt().getTime());
        map.put("updatedAt", record.updatedAt().getTime());
        map.put("diagnostics", parseDiagnostics(record.diagnosticsJson()));
        return map;
    }

    private long hitCount(AgentAck ack, ScriptSessionRecord session) {
        Object value = ack.result() == null ? null : ack.result().get("hitCount");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return session.hitCount();
    }

    private String agentResultJson(AgentAck ack) {
        return PlatformJson.write(ack.result() == null ? Map.of() : ack.result());
    }

    private Object ackDiagnostics(AgentAck ack) {
        return ack.result() == null ? null : ack.result().get("diagnostics");
    }

    private String diagnosticsJson(AgentAck ack) {
        return writeJsonOrEmpty(ackDiagnostics(ack));
    }

    private String writeDiagnostics(List<ScriptDiagnostic> diagnostics) {
        return writeJsonOrEmpty(diagnostics);
    }

    private String writeJsonOrEmpty(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<ScriptDiagnostic> parseDiagnostics(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            String json = raw instanceof String text ? text : objectMapper.writeValueAsString(raw);
            if (json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String stringOr(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private RequestContext systemContext() {
        return new RequestContext("system", "", "127.0.0.1", "header-dev", "platform-sweep");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static CapabilityProfile parseProfile(Object value, CapabilityProfile fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return CapabilityProfile.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw PlatformException.badRequest("INVALID_FIELD",
                    "capabilityProfile must be SAFE, EXTENDED or UNRESTRICTED");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + name);
        }
        return value;
    }

    private static String requiredString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        return String.valueOf(value);
    }

    private static String optionalString(Map<String, Object> request, String key, String defaultValue) {
        Object value = request.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private static Map<String, Object> requiredMap(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static long optionalPositiveLong(Map<String, Object> request, String key, long defaultValue) {
        Object value = request.get(key);
        if (value == null) {
            return defaultValue;
        }
        long parsed = value instanceof Number number ? number.longValue()
                : Long.parseLong(String.valueOf(value));
        if (parsed <= 0) {
            throw PlatformException.badRequest("INVALID_FIELD", key + " must be > 0");
        }
        return parsed;
    }

    // ------------------------------------------------------------------ ack carrier

    /** Decodes an agent ack into a success/timeout outcome carrying the raw result map. */
    private static final class AgentAck {
        private final boolean failed;
        private final String errorMessage;
        private final Map<String, Object> result;

        private AgentAck(boolean failed, String errorMessage, Map<String, Object> result) {
            this.failed = failed;
            this.errorMessage = errorMessage;
            this.result = result;
        }

        static AgentAck success(Map<String, Object> result) {
            return new AgentAck(false, null, result);
        }

        static AgentAck failed(String message, Map<String, Object> result) {
            return new AgentAck(true, message, result);
        }

        static AgentAck timeout(String message) {
            return new AgentAck(true, message, Map.of());
        }

        boolean failed() {
            return failed;
        }

        String errorMessage() {
            return errorMessage;
        }

        Map<String, Object> result() {
            return result;
        }
    }
}
