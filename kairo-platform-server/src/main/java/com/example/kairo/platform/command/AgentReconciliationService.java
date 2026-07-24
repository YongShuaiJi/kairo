package com.example.kairo.platform.command;

import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.api.snapshot.CallSiteSnapshot;
import com.example.kairo.api.snapshot.ChainSnapshot;
import com.example.kairo.platform.persistence.mapper.AgentCommandMapper;
import com.example.kairo.platform.persistence.mapper.AgentReconciliationMapper;
import com.example.kairo.platform.persistence.mapper.AgentRuntimeStateMapper;
import com.example.kairo.platform.persistence.mapper.RuleUnloadMapper;
import com.example.kairo.platform.persistence.mapper.RolloutExecutionMapper;
import com.example.kairo.platform.service.BusinessIdService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V1.7 M1-D &sect;8.4: registration, reconnect and JVM-restart reconciliation.
 *
 * <p>After an agent registers, reconnects or its {@code processStartId} changes, the Platform
 * compares the authoritative <strong>desired</strong> state against the persisted M1-C
 * <strong>actual</strong> snapshot and converges within safe boundaries:
 * <ul>
 *   <li>desired {@code ACTIVE} + actual {@code MISSING/BEHIND} &rarr; re-apply the current
 *       effective rule version ({@code APPLY_RULE}), reusing the rollout payload shape and the
 *       M1-A lease/epoch fencing via {@link AgentCommandService#enqueue};</li>
 *   <li>desired {@code EMPTY/REMOVED} + actual still present &rarr; precise {@code RESET_CLASS}
 *       (per-class, never {@code RESET_ALL});</li>
 *   <li>{@code AHEAD/DIVERGED/TARGET_DRIFTED} &rarr; mark {@code DEGRADED} (upsert
 *       {@code degraded_class}) and <em>do not</em> auto-destructive-overwrite (&sect;4.4).</li>
 * </ul>
 *
 * <p>Desired state is derived from the per-instance authoritative ledger
 * ({@code rule_runtime_status} joined to the formal {@code rule_version} and its
 * {@code rule_target}). Trial, expired or unpromoted script sessions never create a
 * {@code rule_version} row and are therefore structurally excluded from the recovery set
 * (&sect;4.4 / &sect;8.4 item 7). Actual state always comes from the real runtime snapshot, never
 * inferred from "a command was ACKed".
 *
 * <p><b>processStartId</b> (&sect;4.5): a snapshot whose {@code process_start_id} differs from the
 * currently registered instance is from an old process. It is <em>not</em> deleted (that would hide
 * drift); it is marked superseded and the new JVM is read as empty so desired rules are re-applied.
 * The same {@code processStartId} on reconnect preserves the actual history and continues
 * unfinished compensation (the in-flight guard skips a target whose convergence command is still
 * non-terminal).
 *
 * <p><b>Idempotency</b> (&sect;8.4 items 1, 8): keys carry {@code processStartId} so a new JVM
 * lifecycle gets fresh keys. A non-terminal command for the key blocks re-enqueue (no command
 * storm, continue unfinished compensation); a terminal command makes a re-run a no-op unless the
 * snapshot is fresh and still drifted, in which case the target is marked DEGRADED. Convergence
 * commands are enqueued through {@link AgentCommandService#enqueue}, inheriting the M1-A lease/epoch
 * fencing, the M0 capability gate and (for {@code REFRESH_RUNTIME_STATE}) the M1-C snapshot
 * validation+persist on ack.
 */
@Service
public class AgentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(AgentReconciliationService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    static final String REFRESH = "REFRESH_RUNTIME_STATE";
    static final String APPLY_RULE = "APPLY_RULE";
    static final String RESET_CLASS = "RESET_CLASS";
    private static final int CONVERGE_MAX_ATTEMPTS = 10;
    private static final int REFRESH_MAX_ATTEMPTS = 5;

    private final AgentRuntimeStateMapper stateMapper;
    private final AgentCommandService commandService;
    private final AgentCommandMapper commandMapper;
    private final RolloutExecutionMapper rolloutMapper;
    private final RuleUnloadMapper unloadMapper;
    private final AgentReconciliationMapper reconciliationMapper;
    private final PlatformCoreService eventWriter;
    private final BusinessIdService businessIdService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${kairo.platform.reconciliation.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    /** Delay before a reconciliation-requested REFRESH is pollable, so it never pre-empts immediate
     *  convergence commands (APPLY_RULE / RESET_CLASS) dispatched by the rollout or a restore. */
    @Value("${kairo.platform.reconciliation.snapshot-request-delay-ms:5000}")
    private long snapshotRequestDelayMillis;

    @Autowired
    public AgentReconciliationService(AgentRuntimeStateMapper stateMapper,
                                     AgentCommandService commandService,
                                     AgentCommandMapper commandMapper,
                                     RolloutExecutionMapper rolloutMapper,
                                     RuleUnloadMapper unloadMapper,
                                     AgentReconciliationMapper reconciliationMapper,
                                     PlatformCoreService eventWriter,
                                     BusinessIdService businessIdService) {
        this(stateMapper, commandService, commandMapper, rolloutMapper, unloadMapper,
                reconciliationMapper, eventWriter, businessIdService, Clock.systemUTC());
    }

    AgentReconciliationService(AgentRuntimeStateMapper stateMapper,
                               AgentCommandService commandService,
                               AgentCommandMapper commandMapper,
                               RolloutExecutionMapper rolloutMapper,
                               RuleUnloadMapper unloadMapper,
                               AgentReconciliationMapper reconciliationMapper,
                               PlatformCoreService eventWriter,
                               BusinessIdService businessIdService, Clock clock) {
        this.stateMapper = stateMapper;
        this.commandService = commandService;
        this.commandMapper = commandMapper;
        this.rolloutMapper = rolloutMapper;
        this.unloadMapper = unloadMapper;
        this.reconciliationMapper = reconciliationMapper;
        this.eventWriter = eventWriter;
        this.businessIdService = businessIdService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${kairo.platform.reconciliation.scheduler.fixed-delay-ms:30000}",
            initialDelayString = "${kairo.platform.reconciliation.scheduler.initial-delay-ms:30000}")
    public void scheduledReconcile() {
        if (!schedulerEnabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcileOnlineAgents();
        } catch (RuntimeException e) {
            log.warn("V1.7 M1-D scheduled reconciliation failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /** Reconcile every online agent once. Exposed for the manual scheduler run-once path. */
    public Map<String, Object> reconcileOnlineAgents() {
        RequestContext context = systemContext();
        int agents = 0;
        int applied = 0;
        int reset = 0;
        int degraded = 0;
        for (Map<String, Object> row : reconciliationMapper.findOnlineAgentsForReconciliation()) {
            agents++;
            String agentId = String.valueOf(row.get("agent_id"));
            try {
                ReconciliationResult result = reconcileAgent(context, agentId);
                applied += result.applied();
                reset += result.reset();
                degraded += result.degraded();
            } catch (RuntimeException e) {
                log.warn("Reconciliation of agent {} failed: {}", agentId, e.getMessage(), e);
            }
        }
        return Map.of("agents", agents, "applied", applied, "reset", reset, "degraded", degraded);
    }

    /**
     * Hook invoked after a successful agent registration (&sect;8.4 item 1). Schedules a runtime
     * snapshot request and a reconciliation pass to run <em>after</em> the registration transaction
     * commits, so a reconciliation or snapshot-request failure (e.g. the agent did not advertise
     * the {@code REFRESH_RUNTIME_STATE} capability) never rolls back registration. Concurrent
     * registrations are deduped by the REFRESH idempotency key (no command storm).
     */
    public void onAgentRegistered(RequestContext context, String agentId) {
        Runnable afterRegister = () -> {
            try {
                requestSnapshot(context, agentId);
            } catch (RuntimeException e) {
                // Best-effort: a snapshot request must not break registration or reconciliation.
                log.warn("Post-registration snapshot request for agent {} failed: {}", agentId, e.getMessage());
            }
            try {
                reconcileAgent(context, agentId);
            } catch (RuntimeException e) {
                log.warn("Post-registration reconciliation of agent {} failed: {}", agentId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterRegister.run();
                }
            });
        } else {
            afterRegister.run();
        }
    }

    /**
     * Enqueue one deduped {@code REFRESH_RUNTIME_STATE} so the Platform receives a fresh actual
     * snapshot. The idempotency key is scoped to the agent + current processStartId, so concurrent
     * registrations (or a re-run) do not produce a command storm.
     */
    public void requestSnapshot(RequestContext context, String agentId) {
        Map<String, Object> registration = stateMapper.findInstanceRegistrationByAgent(agentId);
        if (registration == null || registration.get("process_start_id") == null) {
            return; // not registered; nothing to snapshot
        }
        String processStartId = String.valueOf(registration.get("process_start_id"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", REFRESH);
        // Delay the snapshot request so it is polled AFTER immediate convergence commands
        // (available_at orders pollCandidates); a registration-time snapshot must not pre-empt a
        // restored operation's APPLY_RULE or a discovery flow.
        Instant availableAt = clock.instant().plusMillis(snapshotRequestDelayMillis);
        commandService.enqueueRuntimeStateRefreshIfIdle(context, agentId, payload,
                refreshIdempotencyKey(agentId, processStartId), REFRESH_MAX_ATTEMPTS, availableAt);
    }

    /**
     * Reconcile one agent: compare desired vs the persisted actual snapshot and converge. Safe to
     * call repeatedly (idempotent per &sect;8.4 item 8): in-flight convergence commands are skipped,
     * IN_SYNC targets are no-ops, and a fresh snapshot that still shows drift after a terminal
     * convergence command marks the target DEGRADED rather than re-enqueuing.
     */
    public ReconciliationResult reconcileAgent(RequestContext context, String agentId) {
        Map<String, Object> registration = stateMapper.findInstanceRegistrationByAgent(agentId);
        if (registration == null || registration.get("process_start_id") == null) {
            return ReconciliationResult.empty();
        }
        String instanceId = String.valueOf(registration.get("instance_id"));
        String currentProcessStartId = String.valueOf(registration.get("process_start_id"));

        Map<String, Object> snapshotRow = stateMapper.findAgentRuntimeState(agentId);
        if (snapshotRow == null) {
            // No actual yet: the agent registered but has not acked a REFRESH. Request one and
            // skip convergence until a real actual arrives (we never infer actual from acks).
            // Best-effort: an agent that did not advertise REFRESH_RUNTIME_STATE cannot be snap-
            // shotted; reconciliation simply waits for the scheduler / a manual refresh.
            try {
                requestSnapshot(context, agentId);
            } catch (RuntimeException e) {
                log.debug("Cannot request runtime snapshot for agent {} ({}); skipping convergence",
                        agentId, e.getMessage());
            }
            return new ReconciliationResult(0, 0, 0, List.of("snapshot_pending"));
        }

        String snapshotProcessStartId = String.valueOf(snapshotRow.get("process_start_id"));
        Timestamp snapshotReceivedAt = asTimestamp(snapshotRow.get("received_at"));
        ActualState actual;
        if (!currentProcessStartId.equals(snapshotProcessStartId)) {
            // §4.5 / §8.4 item 3: a new JVM lifecycle. The persisted snapshot is from an old
            // process; do NOT delete it (forbidden), mark it superseded and read the new JVM as
            // empty so desired rules are re-applied.
            markSuperseded(context, agentId, snapshotProcessStartId, currentProcessStartId);
            actual = ActualState.empty();
        } else {
            actual = parseActual(snapshotRow);
            if (actual.invalidReason() != null) {
                return new ReconciliationResult(0, 0, 0, List.of(actual.invalidReason()));
            }
        }

        DesiredState desired = computeDesired(instanceId);

        // Recompute the degraded set from the current snapshot each pass: clear every prior marker
        // and re-mark only the targets that are still AHEAD/DIVERGED/TARGET_DRIFTED. A class that
        // recovered (no longer divergent in the fresh snapshot) is therefore cleared.
        reconciliationMapper.deleteDegradedForAgent(agentId);

        int applied = 0;
        int reset = 0;
        int degraded = 0;
        List<String> notes = new ArrayList<>(desired.invalidReasons());

        // Desired ACTIVE targets: re-apply / verify against the actual.
        for (DesiredChain desiredChain : desired.activeChains()) {
            ChainSnapshot actualChain = actual.chainByTarget(desiredChain.targetKey);
            Map<String, Long> actualVersions = actualRuleVersions(actualChain, actual);
            Decision decision = decide(desiredChain, actualChain, actualVersions);
            switch (decision.action) {
                case APPLY -> {
                    // One APPLY_RULE per missing/behind desired rule (matches the rollout: one
                    // rule per command; the agent accumulates them into one chain). The
                    // idempotency key is scoped to (agent, target, ruleId, version, processStartId).
                    for (DesiredRule rule : decision.applyRules) {
                        String ruleKey = desiredChain.targetKey + ":" + rule.ruleId() + ":" + rule.version();
                        EnqueueOutcome outcome = enqueueConvergence(context, agentId, currentProcessStartId,
                                APPLY_RULE, ruleKey, snapshotReceivedAt,
                                () -> buildApplyRulePayload(agentId, instanceId, desiredChain, rule));
                        if (outcome == EnqueueOutcome.ENQUEUED) {
                            applied++;
                        } else if (outcome == EnqueueOutcome.TERMINAL_FRESH) {
                            markDegraded(context, agentId, desiredChain.className,
                                    "RECONCILIATION_DIVERGED: apply did not converge "
                                            + "(fresh snapshot still missing " + rule.ruleId() + ")");
                            degraded++;
                        }
                        // IN_FLIGHT_SKIP and TERMINAL_STALE: leave for the next (post-REFRESH) pass.
                    }
                }
                case DEGRADED -> {
                    markDegraded(context, agentId, desiredChain.className, decision.reason);
                    degraded++;
                }
                case IN_SYNC -> {
                    // IN_SYNC: no marker (the pass-wide clear already removed any prior one).
                }
            }
        }

        // Actual chains not covered by any desired ACTIVE target.
        for (ChainSnapshot actualChain : actual.chains) {
            String targetKey = targetKey(actualChain);
            if (desired.activeChain(targetKey) != null) {
                continue; // handled above
            }
            if (desired.isRemoved(targetKey)) {
                // §8.4 item 5: desired EMPTY/REMOVED + actual present -> precise unload.
                EnqueueOutcome outcome = enqueueConvergence(context, agentId, currentProcessStartId,
                        RESET_CLASS, targetKey, snapshotReceivedAt,
                        () -> buildResetClassPayloadFromActual(agentId, instanceId, actualChain));
                if (outcome == EnqueueOutcome.ENQUEUED) {
                    reset++;
                } else if (outcome == EnqueueOutcome.TERMINAL_FRESH) {
                    markDegraded(context, agentId, actualChain.className(),
                            "RECONCILIATION_DIVERGED: unload did not converge (fresh snapshot still carries the chain)");
                    degraded++;
                }
            } else {
                // §8.4 item 6: actual carries a chain the Platform did not desire -> AHEAD. Mark
                // DEGRADED; never auto-destructive-overwrite (no RESET_ALL, no blind RESET_CLASS).
                markDegraded(context, agentId, actualChain.className(),
                        "RECONCILIATION_AHEAD: actual chain not in desired state");
                degraded++;
            }
        }

        return new ReconciliationResult(applied, reset, degraded, notes);
    }

    // -------------------------------------------------------- desired vs actual decision

    private Decision decide(DesiredChain desiredChain, ChainSnapshot actualChain,
                            Map<String, Long> actualVersions) {
        if (actualChain == null) {
            // The whole chain is missing: re-apply every desired rule (one APPLY_RULE each,
            // matching the rollout, which enqueues one rule per command and lets the agent
            // accumulate them into one chain).
            return Decision.apply(new ArrayList<>(desiredChain.rules()),
                    "MISSING: desired ACTIVE chain absent from actual snapshot");
        }
        if (actualChain.degradedReason() != null && !actualChain.degradedReason().isBlank()) {
            // §4.4 / §8.4 item 6: the agent reports the chain degraded (e.g. TARGET_DRIFTED).
            return Decision.degraded(
                    "RECONCILIATION_TARGET_DRIFTED: actual chain reports degraded reason: "
                            + actualChain.degradedReason());
        }
        Map<String, Long> desiredVersions = desiredChain.ruleVersions();
        for (Map.Entry<String, Long> actual : actualVersions.entrySet()) {
            Long desired = desiredVersions.get(actual.getKey());
            if (desired == null) {
                // actual carries a rule not desired -> AHEAD/DIVERGED (do not auto-overwrite).
                return Decision.degraded(
                        "RECONCILIATION_DIVERGED: actual chain carries rule not in desired: " + actual.getKey());
            }
            if (actual.getValue() > desired) {
                return Decision.degraded(
                        "RECONCILIATION_AHEAD: actual rule " + actual.getKey()
                                + " version " + actual.getValue() + " > desired " + desired);
            }
        }
        // Not diverged: collect the desired rules that are missing or behind.
        List<DesiredRule> toApply = new ArrayList<>();
        for (DesiredRule rule : desiredChain.rules()) {
            Long actual = actualVersions.get(rule.ruleId());
            if (actual == null || actual < rule.version()) {
                toApply.add(rule);
            }
        }
        if (!toApply.isEmpty()) {
            return Decision.apply(toApply, "BEHIND: actual chain missing desired rule/version");
        }
        return Decision.inSync();
    }

    /** ruleId &rarr; version for an actual chain's rules, resolved from the snapshot's rules[]. */
    private Map<String, Long> actualRuleVersions(ChainSnapshot actualChain, ActualState actual) {
        Map<String, Long> versions = new LinkedHashMap<>();
        if (actualChain == null || actualChain.ruleIds() == null) {
            return versions;
        }
        for (String ruleId : actualChain.ruleIds()) {
            versions.put(normalizeRuleId(ruleId), actual.ruleVersions.getOrDefault(ruleId, 0L));
        }
        return versions;
    }

    /**
     * Normalize a rule id for desired&harr;actual comparison. The rollout's {@code APPLY_RULE}
     * payload sets the agent rule id to {@code ruleId + ":" + version} (e.g. {@code "r:1"}), while
     * the authoritative {@code rule_runtime_status.rule_id} is the plain rule id ({@code "r"}).
     * Strip a trailing {@code :<digits>} version suffix so the two forms compare equal; a plain id
     * is returned unchanged.
     */
    private static String normalizeRuleId(String ruleId) {
        if (ruleId == null) {
            return "";
        }
        int colon = ruleId.lastIndexOf(':');
        if (colon <= 0) {
            return ruleId;
        }
        String suffix = ruleId.substring(colon + 1);
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return ruleId; // not a version suffix; leave intact
            }
        }
        return ruleId.substring(0, colon);
    }

    // -------------------------------------------------------- convergence enqueue + payloads

    /**
     * The outcome of attempting to enqueue a convergence command for a key.
     * <ul>
     *   <li>{@link #ENQUEUED} - a new command was enqueued.</li>
     *   <li>{@link #IN_FLIGHT_SKIP} - a non-terminal command for the key already exists; leave it
     *       (continue unfinished compensation, no storm).</li>
     *   <li>{@link #TERMINAL_STALE} - the prior command is terminal and the snapshot predates its
     *       completion; the apply may have succeeded after the snapshot, so request a fresh
     *       snapshot and skip this pass (do not mark DEGRADED).</li>
     *   <li>{@link #TERMINAL_FRESH} - the prior command is terminal and the snapshot is at least
     *       as fresh as its completion, yet the target is still drifted: genuine non-convergence
     *       (caller marks DEGRADED; no re-enqueue - &sect;8.4 item 8 no-op after completion).</li>
     * </ul>
     */
    enum EnqueueOutcome { ENQUEUED, IN_FLIGHT_SKIP, TERMINAL_STALE, TERMINAL_FRESH }

    private EnqueueOutcome enqueueConvergence(RequestContext context, String agentId,
                                              String processStartId, String commandType, String targetKey,
                                              Timestamp snapshotReceivedAt,
                                              java.util.function.Supplier<Map<String, Object>> payloadSupplier) {
        String idempotencyKey = convergenceIdempotencyKey(commandType, agentId, targetKey, processStartId);
        Map<String, Object> existing = commandMapper.commandByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            String status = String.valueOf(existing.get("status"));
            if ("PENDING".equals(status) || "DISPATCHED".equals(status)) {
                return EnqueueOutcome.IN_FLIGHT_SKIP;
            }
            // Terminal. If the snapshot predates the command's completion it is stale: the apply may
            // have landed after the snapshot. Request a fresh snapshot and skip this pass.
            Timestamp commandCompleted = asTimestamp(existing.get("completed_at"));
            if (commandCompleted == null) {
                commandCompleted = asTimestamp(existing.get("updated_at"));
            }
            boolean snapshotFresh = snapshotReceivedAt != null && commandCompleted != null
                    && !snapshotReceivedAt.before(commandCompleted);
            if (!snapshotFresh) {
                requestSnapshot(context, agentId);
                return EnqueueOutcome.TERMINAL_STALE;
            }
            return EnqueueOutcome.TERMINAL_FRESH;
        }
        commandService.enqueue(context, agentId, commandType, payloadSupplier.get(),
                idempotencyKey, CONVERGE_MAX_ATTEMPTS, clock.instant());
        return EnqueueOutcome.ENQUEUED;
    }

    /**
     * Build an {@code APPLY_RULE} payload with the same shape as a rollout
     * ({@link RolloutExecutor#ruleCommandPayload}), so the agent applies the identical rule. The
     * rule script and target come from the authoritative {@code rule_version} + {@code rule_target}.
     */
    private Map<String, Object> buildApplyRulePayload(String agentId, String instanceId,
                                                       DesiredChain desiredChain, DesiredRule rule) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", APPLY_RULE);
        payload.put("operationPlanId", "reconcile:" + agentId + ":" + desiredChain.targetKey);
        payload.put("agentId", agentId);
        payload.put("instanceId", instanceId);
        payload.put("resourceType", "rule");
        payload.put("resourceId", rule.ruleId());
        payload.put("resourceVersion", rule.version());
        payload.put("reconcile", true);
        payload.put("rule", rulePayload(desiredChain, rule));
        return payload;
    }

    private Map<String, Object> rulePayload(DesiredChain desiredChain, DesiredRule rule) {
        Map<String, Object> script = readMapSafe(String.valueOf(rule.scriptJson()));
        Map<String, Object> matcher = readMapSafe(desiredChain.targetMatcherJson);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", rule.ruleId() + ":" + rule.version());
        payload.put("version", rule.version());
        payload.put("name", "reconcile-" + rule.ruleId());
        payload.put("description", "Kairo 平台对账重新应用");
        payload.put("classId", String.valueOf(matcher.getOrDefault(
                "classId", desiredChain.className)));
        payload.put("className", desiredChain.className);
        payload.put("classLoaderId", String.valueOf(matcher.getOrDefault("classLoaderId", "")));
        payload.put("methodName", desiredChain.methodName);
        payload.put("methodDescriptor", String.valueOf(matcher.getOrDefault(
                "descriptor", desiredChain.descriptor)));
        EnhancementLocation location = desiredChain.location;
        payload.put("phase", location != null
                ? location.toLegacyPhase().name()
                : String.valueOf(script.getOrDefault("phase", "BEFORE")));
        if (location != null) {
            payload.put("location", location.name());
            String callSiteJson = desiredChain.callSiteSelectorJson;
            if (callSiteJson != null && !callSiteJson.isBlank() && !"null".equals(callSiteJson)) {
                payload.put("callSiteSelector", readMapSafe(callSiteJson));
            }
        }
        payload.put("script", scriptText(script));
        payload.put("priority", 0);
        payload.put("percentage", 100);
        payload.put("maxHits", 0);
        payload.put("expireAt", 0L);
        payload.put("failOpen", true);
        payload.put("enabled", true);
        return payload;
    }

    /** Mirror {@link RolloutExecutor}'s script-text derivation so the agent compiles the same script. */
    private String scriptText(Map<String, Object> script) {
        Object value = script.get("script");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        Object type = script.get("type");
        if ("RETURN".equals(type) && script.containsKey("value")) {
            return "return mock.returnValue(" + PlatformJson.write(script.get("value")) + ")";
        }
        if ("THROW".equals(type) && script.containsKey("exception")) {
            return "return mock.throwException(\"" + script.get("exception")
                    + "\", \"injected by Kairo\")";
        }
        return "return mock.proceed(args)";
    }

    private Map<String, Object> buildResetClassPayloadFromActual(String agentId, String instanceId,
                                                                 ChainSnapshot actualChain) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", RESET_CLASS);
        payload.put("operationPlanId", "reconcile:" + agentId + ":" + targetKey(actualChain));
        payload.put("instanceId", instanceId);
        payload.put("classId", actualChain.className());
        payload.put("className", actualChain.className());
        payload.put("reconcile", true);
        return payload;
    }

    // -------------------------------------------------------- desired + actual parsing

    private DesiredState computeDesired(String instanceId) {
        List<Map<String, Object>> rows = reconciliationMapper.findInstanceRuntimeStatuses(instanceId);
        Map<String, DesiredChain.Builder> active = new LinkedHashMap<>();
        Map<String, String> removed = new LinkedHashMap<>(); // targetKey -> className
        List<String> invalidReasons = new ArrayList<>();
        for (Map<String, Object> raw : rows) {
            Map<String, Object> row = lowerKeys(raw);
            String ruleId = String.valueOf(row.get("rule_id"));
            long version = asLong(row.get("rule_version"));
            String runtimeStatus = String.valueOf(row.get("runtime_status"));
            String versionStatus = String.valueOf(row.get("version_status"));
            String targetId = nullableText(row.get("target_id"));
            if (targetId.isBlank()) {
                String reason = "INVALID_DESIRED_TARGET: rule " + ruleId + " version " + version
                        + " has no rule_target; reconciliation skipped it";
                invalidReasons.add(reason);
                log.warn(reason);
                continue;
            }
            String className = String.valueOf(row.get("target_class_name"));
            String methodName = String.valueOf(row.get("target_method_name"));
            Map<String, Object> targetMatcher = readMapSafe(
                    String.valueOf(row.getOrDefault("target_matcher_json", "{}")));
            String descriptor = String.valueOf(targetMatcher.getOrDefault("descriptor", ""));
            String classLoaderId = String.valueOf(targetMatcher.getOrDefault("classLoaderId", ""));
            String locationText = String.valueOf(row.getOrDefault("target_location", ""));
            String rawCallSiteJson = String.valueOf(row.getOrDefault("target_call_site_selector_json", ""));
            String callSiteJson = "null".equals(rawCallSiteJson) ? "" : rawCallSiteJson;
            EnhancementLocation location = parseLocation(locationText);
            String targetKey = targetKey(className, classLoaderId, methodName, descriptor,
                    location != null ? location.name() : "", callSiteJson);
            boolean activeRule = "ACTIVE".equals(runtimeStatus) && "ENABLED".equals(versionStatus);
            if (activeRule) {
                DesiredChain.Builder builder = active.computeIfAbsent(targetKey, k -> new DesiredChain.Builder(
                        targetKey, className, methodName, descriptor, location, callSiteJson,
                        String.valueOf(row.getOrDefault("target_matcher_json", "{}"))));
                builder.add(new DesiredRule(ruleId, version, String.valueOf(row.get("script_json"))));
            } else {
                // REMOVED runtime status, or a DISABLED rule version that was applied: desired is
                // EMPTY/REMOVED for this target (unload if the actual still carries it).
                removed.putIfAbsent(targetKey, className);
            }
        }
        // A target with at least one ACTIVE ENABLED rule is desired ACTIVE, even if other rows at
        // the target are REMOVED/DISABLED; the removed entries do not demote it.
        for (String targetKey : active.keySet()) {
            removed.remove(targetKey);
        }
        List<DesiredChain> activeChains = new ArrayList<>();
        for (DesiredChain.Builder builder : active.values()) {
            activeChains.add(builder.build());
        }
        return new DesiredState(activeChains, removed, invalidReasons);
    }

    private ActualState parseActual(Map<String, Object> snapshotRow) {
        String json = String.valueOf(snapshotRow.get("snapshot_json"));
        if (json == null || json.isBlank() || "null".equals(json)) {
            return ActualState.invalid(
                    "INVALID_ACTUAL_SNAPSHOT: persisted runtime snapshot is empty; reconciliation skipped");
        }
        try {
            AgentRuntimeSnapshot snapshot = MAPPER.readValue(json, AgentRuntimeSnapshot.class);
            if (snapshot == null || snapshot.agentId() == null || snapshot.agentId().isBlank()
                    || snapshot.processStartId() == null || snapshot.processStartId().isBlank()
                    || snapshot.chains() == null || snapshot.rules() == null) {
                return ActualState.invalid(
                        "INVALID_ACTUAL_SNAPSHOT: persisted runtime snapshot is incomplete; reconciliation skipped");
            }
            return ActualState.of(snapshot);
        } catch (RuntimeException e) {
            log.warn("Cannot parse runtime state snapshot for reconciliation: {}", e.getMessage());
            return ActualState.invalid(
                    "INVALID_ACTUAL_SNAPSHOT: persisted runtime snapshot is malformed; reconciliation skipped");
        } catch (Exception e) {
            log.warn("Cannot parse runtime state snapshot for reconciliation: {}", e.getMessage());
            return ActualState.invalid(
                    "INVALID_ACTUAL_SNAPSHOT: persisted runtime snapshot is malformed; reconciliation skipped");
        }
    }

    // -------------------------------------------------------- degraded / superseded markers

    private void markDegraded(RequestContext context, String agentId, String className, String reason) {
        if (className == null || className.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        String id = businessIdService.nextId("degraded_class",
                "reconcile:" + agentId + ":" + className);
        String boundedReason = truncate(reason, 2000);
        Timestamp observedAt = Timestamp.from(now);
        int updated = reconciliationMapper.updateDegradedClass(
                agentId, className, boundedReason, observedAt);
        if (updated == 0) {
            try {
                reconciliationMapper.insertDegradedClass(
                        id, agentId, className, boundedReason, observedAt);
            } catch (DuplicateKeyException concurrentInsert) {
                reconciliationMapper.updateDegradedClass(
                        agentId, className, boundedReason, observedAt);
            }
        }
        eventWriter.recordEvent(context, "reconciliation.degraded", "agent_instance", agentId, 1,
                Map.of("className", className), Map.of("className", className, "reason", reason),
                "DEGRADED", "对账发现漂移，已标记降级，不自动覆盖",
                Map.of("agentId", agentId, "className", className, "reason", reason));
    }

    private void markSuperseded(RequestContext context, String agentId,
                                String snapshotProcessStartId, String currentProcessStartId) {
        eventWriter.recordEvent(context, "agent_runtime_state.superseded", "agent_instance", agentId, 1,
                Map.of("snapshotProcessStartId", String.valueOf(snapshotProcessStartId)),
                Map.of("currentProcessStartId", String.valueOf(currentProcessStartId),
                        "snapshotProcessStartId", String.valueOf(snapshotProcessStartId)),
                "SUCCESS", "新 processStartId，旧 actual 标记为历史/失效，按新 JVM 空状态对账",
                Map.of("agentId", agentId, "snapshotProcessStartId", snapshotProcessStartId,
                        "currentProcessStartId", currentProcessStartId));
    }

    // -------------------------------------------------------- keys + helpers

    static String refreshIdempotencyKey(String agentId, String processStartId) {
        return "reconcile:" + REFRESH + ":" + agentId + ":" + processStartId;
    }

    static String convergenceIdempotencyKey(String commandType, String agentId,
                                            String targetKey, String processStartId) {
        return "reconcile:" + commandType + ":" + agentId + ":" + targetKey + ":" + processStartId;
    }

    private static String targetKey(ChainSnapshot chain) {
        String callSiteKey = callSiteKey(chain.callSite());
        return targetKey(chain.className(), chain.loaderId(), chain.methodName(),
                chain.descriptor(), chain.location(), callSiteKey);
    }

    private static String targetKey(String className, String classLoaderId, String methodName,
                                   String descriptor, String locationName, String callSiteKey) {
        return className + "|" + nullToEmpty(classLoaderId) + "|" + nullToEmpty(methodName)
                + "|" + nullToEmpty(descriptor) + "|" + nullToEmpty(locationName)
                + "|" + nullToEmpty(callSiteKey);
    }

    private static String callSiteKey(CallSiteSnapshot callSite) {
        if (callSite == null) {
            return "";
        }
        return callSite.owner() + "." + callSite.name() + callSite.descriptor()
                + callSite.opcode() + callSite.occurrenceIndex();
    }

    private static String callSiteKey(String callSiteSelectorJson) {
        if (callSiteSelectorJson == null || callSiteSelectorJson.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> cs = PlatformJson.readMap(callSiteSelectorJson);
            return String.valueOf(cs.getOrDefault("owner", "")) + "."
                    + cs.getOrDefault("name", "") + cs.getOrDefault("descriptor", "")
                    + cs.getOrDefault("opcode", "") + cs.getOrDefault("occurrenceIndex", 0);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static EnhancementLocation parseLocation(String text) {
        if (text == null || text.isBlank() || "null".equals(text)) {
            return null;
        }
        try {
            return EnhancementLocation.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private RequestContext systemContext() {
        return new RequestContext("system", "reconcile-" + UUID.randomUUID(),
                "127.0.0.1", "system", "reconciliation");
    }

    private static Map<String, Object> lowerKeys(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (row != null) {
            row.forEach((key, value) -> normalized.put(key == null ? null : key.toLowerCase(), value));
        }
        return normalized;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    private static Timestamp asTimestamp(Object value) {
        if (value instanceof Timestamp ts) {
            return ts;
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime());
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullableText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Parse a matcher/call-site JSON string that may be null, "null", blank or malformed. */
    private static Map<String, Object> readMapSafe(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> map = PlatformJson.readMap(json);
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // -------------------------------------------------------- inner types

    /** Summary of one reconciliation pass for an agent. */
    public record ReconciliationResult(int applied, int reset, int degraded, List<String> notes) {
        static ReconciliationResult empty() {
            return new ReconciliationResult(0, 0, 0, List.of());
        }
    }

    private enum ConvergenceAction { APPLY, DEGRADED, IN_SYNC }

    private record Decision(ConvergenceAction action, List<DesiredRule> applyRules, String reason) {
        static Decision apply(List<DesiredRule> applyRules, String reason) {
            return new Decision(ConvergenceAction.APPLY, applyRules, reason);
        }
        static Decision degraded(String reason) {
            return new Decision(ConvergenceAction.DEGRADED, List.of(), reason);
        }
        static Decision inSync() {
            return new Decision(ConvergenceAction.IN_SYNC, List.of(), "");
        }
    }

    /** One desired rule version targeting a chain. */
    record DesiredRule(String ruleId, long version, String scriptJson) {
    }

    /** The desired state for one target: the active rules that should be applied there. */
    static final class DesiredChain {
        final String targetKey;
        final String className;
        final String methodName;
        final String descriptor;
        final EnhancementLocation location;
        final String callSiteSelectorJson;
        final String targetMatcherJson;
        private final List<DesiredRule> rules = new ArrayList<>();

        DesiredChain(String targetKey, String className, String methodName, String descriptor,
                     EnhancementLocation location, String callSiteSelectorJson, String targetMatcherJson) {
            this.targetKey = targetKey;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.location = location;
            this.callSiteSelectorJson = callSiteSelectorJson;
            this.targetMatcherJson = targetMatcherJson;
        }

        void add(DesiredRule rule) {
            rules.add(rule);
        }

        /** The desired rules that should be applied at this target (in insertion order). */
        List<DesiredRule> rules() {
            return rules;
        }

        Map<String, Long> ruleVersions() {
            Map<String, Long> versions = new LinkedHashMap<>();
            for (DesiredRule rule : rules) {
                versions.put(normalizeRuleId(rule.ruleId()), rule.version());
            }
            return versions;
        }

        static final class Builder {
            private final DesiredChain chain;

            Builder(String targetKey, String className, String methodName, String descriptor,
                    EnhancementLocation location, String callSiteSelectorJson, String targetMatcherJson) {
                this.chain = new DesiredChain(targetKey, className, methodName, descriptor, location,
                        callSiteSelectorJson, targetMatcherJson);
            }

            void add(DesiredRule rule) {
                chain.add(rule);
            }

            DesiredChain build() {
                return chain;
            }
        }
    }

    private record DesiredState(List<DesiredChain> activeChains, Map<String, String> removed,
                                List<String> invalidReasons) {
        DesiredChain activeChain(String targetKey) {
            for (DesiredChain chain : activeChains) {
                if (chain.targetKey.equals(targetKey)) {
                    return chain;
                }
            }
            return null;
        }

        boolean isRemoved(String targetKey) {
            return removed.containsKey(targetKey);
        }
    }

    /** The parsed actual snapshot: chains keyed by target + a ruleId&rarr;version map from rules[]. */
    static final class ActualState {
        final List<ChainSnapshot> chains;
        final Map<String, Long> ruleVersions;
        private final String invalidReason;
        private final Map<String, ChainSnapshot> chainsByTarget;

        private ActualState(List<ChainSnapshot> chains, Map<String, Long> ruleVersions,
                            String invalidReason) {
            this.chains = chains;
            this.ruleVersions = ruleVersions;
            this.invalidReason = invalidReason;
            this.chainsByTarget = new LinkedHashMap<>();
            for (ChainSnapshot chain : chains) {
                chainsByTarget.putIfAbsent(targetKey(chain), chain);
            }
        }

        static ActualState empty() {
            return new ActualState(List.of(), Map.of(), null);
        }

        static ActualState invalid(String reason) {
            return new ActualState(List.of(), Map.of(), reason);
        }

        static ActualState of(AgentRuntimeSnapshot snapshot) {
            Map<String, Long> ruleVersions = new LinkedHashMap<>();
            if (snapshot.rules() != null) {
                for (com.example.kairo.api.snapshot.RuleSnapshot rule : snapshot.rules()) {
                    ruleVersions.put(rule.ruleId(), rule.ruleVersion());
                }
            }
            List<ChainSnapshot> chains = snapshot.chains() == null ? List.of() : snapshot.chains();
            return new ActualState(chains, ruleVersions, null);
        }

        ChainSnapshot chainByTarget(String targetKey) {
            return chainsByTarget.get(targetKey);
        }

        String invalidReason() {
            return invalidReason;
        }
    }
}
