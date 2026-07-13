package com.example.kairo.agent.core.script;

import com.example.kairo.agent.core.SyntheticBridgePolicy;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.ScriptDiagnostic;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.api.ScriptSessionSpec;
import com.example.kairo.api.ScriptSessionStatus;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.groovy.CompiledMockScript;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the local lifecycle of temporary {@code ScriptSession}s: real-JVM trials of a script
 * against one target method, bounded by a TTL and a hit cap that the agent enforces on its own.
 *
 * <p>State machine (see {@link ScriptSessionStatus}):
 * <pre>
 *   CREATED --validate()--&gt; VALIDATED --apply()--&gt; APPLIED --(TTL | maxHits)--&gt; EXPIRED
 *                                                  \--revert()--&gt; REVERTED
 *                                                  \--promote()--&gt; REVERTED (formal rule persists)
 *   any non-terminal --deactivate*()--&gt; REVERTED
 *   validate()/apply() failure --&gt; FAILED
 * </pre>
 *
 * <p>The agent stores each session's deadline locally and drives expiry from a scheduled sweep
 * plus a lazy check on every access, so expiry is independent of Platform or client connectivity:
 * a session expires the same way whether the control plane is online, offline or the creating
 * client has disconnected. Temporary sessions are not persisted; they do not survive an agent
 * restart. Formal rules promoted from a session outlive it under the same rule id and are
 * reconciled by the Platform.
 *
 * <p>Promotion never widens permissions or scope: the formal rule reuses the session's exact
 * capability profile, policy revision, target and script, dropping only the trial's TTL and hit
 * cap. The sessions map doubles as the emergency-deactivation index, keyed by session id and
 * scannable by target for {@link #deactivateTarget(String, String)}.
 */
public final class ScriptSessionManager implements AutoCloseable {

    private final ScriptSessionHost host;
    private final AgentScriptCompilerFactory compilerFactory;
    private final ScriptSessionTargetResolver targetResolver;
    private final Clock clock;
    private final ScriptSessionLimits limits;
    private final SyntheticBridgePolicy syntheticBridgePolicy;
    private final ConcurrentHashMap<String, ScriptSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ScriptSessionManager(ScriptSessionHost host,
                                AgentScriptCompilerFactory compilerFactory,
                                ScriptSessionTargetResolver targetResolver,
                                Clock clock,
                                ScriptSessionLimits limits) {
        this(host, compilerFactory, targetResolver, clock, limits, new SyntheticBridgePolicy());
    }

    /**
     * V1.5 &sect;4.3: construct with a shared {@link SyntheticBridgePolicy} so script
     * sessions honor the same synthetic/bridge/lambda control as the publish and record
     * paths. The host ({@link com.example.kairo.agent.core.AgentRuntime}) passes its own
     * policy so arming {@code allowBridge} applies uniformly.
     */
    public ScriptSessionManager(ScriptSessionHost host,
                                AgentScriptCompilerFactory compilerFactory,
                                ScriptSessionTargetResolver targetResolver,
                                Clock clock,
                                ScriptSessionLimits limits,
                                SyntheticBridgePolicy syntheticBridgePolicy) {
        this.host = Objects.requireNonNull(host, "host");
        this.compilerFactory = Objects.requireNonNull(compilerFactory, "compilerFactory");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.syntheticBridgePolicy = Objects.requireNonNull(syntheticBridgePolicy, "syntheticBridgePolicy");
    }

    // ------------------------------------------------------------------ creation

    /**
     * Create a session in {@code CREATED}. Resolves and validates the target method, enforces the
     * safety limits, and records the local deadline. The script is not compiled or applied yet.
     */
    public ScriptSessionResult create(ScriptSessionSpec spec) {
        ensureOpen();
        Objects.requireNonNull(spec, "spec");
        if (sessions.containsKey(spec.sessionId())) {
            throw new IllegalStateException("Script session already exists: " + spec.sessionId());
        }
        enforceLimits(spec);

        ScriptSessionTarget target = targetResolver.resolve(spec.target());
        ensureMockable(target.method());

        long now = clock.millis();
        long expiresAt = now + spec.ttlMillis();
        ScriptSession session = new ScriptSession(spec, target, now, expiresAt);
        if (sessions.putIfAbsent(spec.sessionId(), session) != null) {
            throw new IllegalStateException("Script session already exists: " + spec.sessionId());
        }
        host.recordSessionEvent("script.session.create", spec.requestedBy(), spec.sessionId(),
                target.className() + "#" + spec.target().methodName(),
                "Created session profile=" + spec.capabilityProfile()
                        + " ttl=" + spec.ttlMillis() + "ms maxHits=" + spec.maxHits());
        return session.toResult();
    }

    // ------------------------------------------------------------------ validate

    /**
     * Dry-run compile the script against the target method's ClassLoader. On success the session
     * becomes {@code VALIDATED}; on compile failure it becomes {@code FAILED} with a structured
     * diagnostic. The script is not yet applied to the live method.
     */
    public ScriptSessionResult validate(String sessionId) {
        ScriptSession session = requireSession(sessionId);
        synchronized (session) {
            ensureOpen();
            requireState(session, ScriptSessionStatus.CREATED, "validate");
            MockRule rule = trialRule(session, session.expiresAt());
            try {
                CompiledMockScript compiled = compilerFactory.compile(session.target().method(), rule);
                session.markValidated(List.of());
                host.recordSessionEvent("script.session.validate", session.spec().requestedBy(), sessionId,
                        null, "Validated script hash=" + compiled.scriptHash());
            } catch (RuntimeException e) {
                ScriptDiagnostic diagnostic = diagnostic(ScriptDiagnostic.Phase.COMPILATION,
                        "SCRIPT_COMPILE_ERROR", rootMessage(e), session,
                        "Fix the script and create a new session; a failed session cannot be retried.");
                session.markFailed(List.of(diagnostic));
                host.recordSessionEvent("script.session.validate.failed", session.spec().requestedBy(),
                        sessionId, null, rootMessage(e));
            }
            return session.toResult();
        }
    }

    // ------------------------------------------------------------------ apply

    /**
     * Publish the script as a bounded trial rule on the live target method. Requires
     * {@code VALIDATED}. On success the session becomes {@code APPLIED}; on publish failure it
     * becomes {@code FAILED}. The rule carries the session's TTL and hit cap, so the dispatcher
     * itself stops matching after the cap even before the manager sweeps.
     */
    public ScriptSessionResult apply(String sessionId) {
        ScriptSession session = requireSession(sessionId);
        synchronized (session) {
            ensureOpen();
            requireState(session, ScriptSessionStatus.VALIDATED, "apply");
            MockRule rule = trialRule(session, session.expiresAt());
            try {
                CompiledRule compiledRule = host.applyTrialRule(
                        session.target().method(), rule, session.spec().requestedBy());
                session.markApplied(compiledRule);
                host.recordSessionEvent("script.session.apply", session.spec().requestedBy(), sessionId,
                        null, "Applied trial rule maxHits=" + session.spec().maxHits()
                                + " expiresAt=" + session.expiresAt());
            } catch (RuntimeException e) {
                ScriptDiagnostic diagnostic = diagnostic(ScriptDiagnostic.Phase.EXECUTION,
                        "SCRIPT_APPLY_ERROR", rootMessage(e), session,
                        "Target instrumentation failed; see agent events for the underlying cause.");
                session.markFailed(List.of(diagnostic));
                host.recordSessionEvent("script.session.apply.failed", session.spec().requestedBy(),
                        sessionId, null, rootMessage(e));
            }
            return session.toResult();
        }
    }

    // ------------------------------------------------------------------ revert

    /**
     * Revert a session: remove the trial rule if applied and mark the session {@code REVERTED}.
     * Idempotent for terminal sessions (returns the current snapshot without error), matching a
     * DELETE request that may be retried after a disconnect.
     */
    public ScriptSessionResult revert(String sessionId) {
        return revert(sessionId, null);
    }

    /**
     * Revert with an explicit actor (used by emergency deactivation and the HTTP delete path).
     * Falls back to the session's requester when no actor is supplied.
     */
    public ScriptSessionResult revert(String sessionId, String actor) {
        ScriptSession session = requireSession(sessionId);
        synchronized (session) {
            ScriptSessionStatus current = session.status();
            if (current.terminal()) {
                return session.toResult();
            }
            String effectiveActor = actor == null ? session.spec().requestedBy() : actor;
            if (current == ScriptSessionStatus.APPLIED) {
                try {
                    host.revertTrialRule(session.spec().sessionId(), effectiveActor);
                } catch (RuntimeException e) {
                    ScriptDiagnostic diagnostic = diagnostic(ScriptDiagnostic.Phase.EXECUTION,
                            "SCRIPT_REVERT_ERROR", rootMessage(e), session,
                            "Trial rule removal failed; the rule may still be applied.");
                    session.markFailed(List.of(diagnostic));
                    host.recordSessionEvent("script.session.revert.failed", effectiveActor, sessionId,
                            null, rootMessage(e));
                    return session.toResult();
                }
            }
            session.markReverted();
            host.recordSessionEvent("script.session.revert", effectiveActor, sessionId,
                    null, "Reverted session");
            return session.toResult();
        }
    }

    // ------------------------------------------------------------------ promote

    /**
     * Promote a validated or applied trial to a formal rule under the same rule id. The formal
     * rule reuses the session's exact capability profile, policy revision, target and script;
     * only the trial's TTL and hit cap are dropped. The session is then {@code REVERTED} &mdash;
     * its trial lifecycle ends &mdash; while the formal rule persists and is from then on managed
     * through the normal rule path (and reconciled by the Platform after an agent restart).
     */
    public ScriptSessionResult promote(String sessionId, String actor) {
        ScriptSession session = requireSession(sessionId);
        synchronized (session) {
            ensureOpen();
            ScriptSessionStatus current = session.status();
            if (current != ScriptSessionStatus.VALIDATED && current != ScriptSessionStatus.APPLIED) {
                throw new IllegalStateException("Cannot promote session " + sessionId
                        + " in state " + current + "; validate first");
            }
            MockRule formal = formalRule(session);
            host.applyTrialRule(session.target().method(), formal, actor);
            session.markReverted();
            host.recordSessionEvent("script.session.promote", actor, sessionId,
                    null, "Promoted to formal rule profile=" + session.spec().capabilityProfile()
                            + " revision=" + session.spec().policyRevision());
            return session.toResult();
        }
    }

    // ------------------------------------------------------------------ queries

    /** Current snapshot, lazily expiring the session if its deadline or hit cap has been reached. */
    public ScriptSessionResult result(String sessionId) {
        ScriptSession session = requireSession(sessionId);
        expireIfDue(session, clock.millis());
        return session.toResult();
    }

    /** Snapshots of all sessions, lazily expired, ordered by session id. */
    public List<ScriptSessionResult> sessions() {
        long now = clock.millis();
        List<ScriptSessionResult> out = new ArrayList<>(sessions.size());
        for (ScriptSession session : sessions.values()) {
            expireIfDue(session, now);
            out.add(session.toResult());
        }
        out.sort(Comparator.comparing(ScriptSessionResult::sessionId));
        return out;
    }

    // ------------------------------------------------------------------ emergency deactivation

    /**
     * Emergency stop: revert every non-terminal session. Applied trial rules are removed so the
     * target methods return to their original behavior. Promoted formal rules are untouched
     * (their sessions are already terminal). Returns the number of sessions deactivated.
     */
    public int deactivateAll(String actor) {
        int count = 0;
        for (ScriptSession session : sessions.values()) {
            if (deactivate(session, actor, "all", null)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Emergency stop for one target: revert every non-terminal session whose target class matches
     * {@code classIdOrName} (by class id or by bare class name). Returns the number deactivated.
     */
    public int deactivateTarget(String classIdOrName, String actor) {
        if (classIdOrName == null || classIdOrName.isBlank()) {
            throw new IllegalArgumentException("classIdOrName is required");
        }
        int count = 0;
        for (ScriptSession session : sessions.values()) {
            if (!matchesTarget(session, classIdOrName)) {
                continue;
            }
            if (deactivate(session, actor, "target", classIdOrName)) {
                count++;
            }
        }
        return count;
    }

    private boolean deactivate(ScriptSession session, String actor, String scope, String target) {
        synchronized (session) {
            if (session.status().terminal()) {
                return false;
            }
            if (session.status() == ScriptSessionStatus.APPLIED) {
                try {
                    host.revertTrialRule(session.spec().sessionId(), actor);
                } catch (RuntimeException e) {
                    host.recordSessionEvent("script.session.deactivate.failed", actor,
                            session.spec().sessionId(), target, rootMessage(e));
                }
            }
            session.markReverted();
            host.recordSessionEvent("script.session.deactivate", actor, session.spec().sessionId(),
                    target, "Emergency deactivated (" + scope + ")");
            return true;
        }
    }

    private boolean matchesTarget(ScriptSession session, String classIdOrName) {
        ScriptSessionTarget target = session.target();
        return classIdOrName.equals(target.classId())
                || classIdOrName.equals(target.className())
                || classIdOrName.equals(session.spec().target().className());
    }

    // ------------------------------------------------------------------ TTL sweep

    /**
     * Sweep all sessions and expire any whose deadline has passed or whose hit cap has been
     * reached. Intended to be invoked periodically by the agent's cleanup executor; also safe to
     * call directly. Does nothing once the manager is closed.
     */
    public void expireDue() {
        if (closed) {
            return;
        }
        long now = clock.millis();
        for (ScriptSession session : sessions.values()) {
            expireIfDue(session, now);
        }
    }

    private void expireIfDue(ScriptSession session, long now) {
        synchronized (session) {
            if (session.status().terminal()) {
                return;
            }
            if (!session.isExpired(now)) {
                return;
            }
            if (session.status() == ScriptSessionStatus.APPLIED) {
                try {
                    host.revertTrialRule(session.spec().sessionId(), "ttl-cleanup");
                } catch (RuntimeException e) {
                    host.recordSessionEvent("script.session.expire.failed", "ttl-cleanup",
                            session.spec().sessionId(), null, rootMessage(e));
                }
            }
            boolean hitCap = session.status() == ScriptSessionStatus.APPLIED
                    && session.appliedRule() != null
                    && session.appliedRule().hits() >= session.spec().maxHits();
            session.markExpired();
            host.recordSessionEvent("script.session.expire", "ttl-cleanup", session.spec().sessionId(),
                    null, hitCap ? "Session expired (hit cap reached)" : "Session expired (TTL elapsed)");
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Drop every session without reverting rules; used by agent reset which clears rules itself. */
    public void clear() {
        sessions.clear();
    }

    /** Close the manager: stop accepting new sessions and drop all state. */
    public void close() {
        closed = true;
        sessions.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------ internals

    private ScriptSession requireSession(String sessionId) {
        ScriptSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Script session not found: " + sessionId);
        }
        return session;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ScriptSessionManager is closed");
        }
    }

    private void requireState(ScriptSession session, ScriptSessionStatus expected, String action) {
        ScriptSessionStatus current = session.status();
        if (current != expected) {
            throw new IllegalStateException("Cannot " + action + " session " + session.spec().sessionId()
                    + " in state " + current + "; expected " + expected);
        }
    }

    private void ensureMockable(java.lang.reflect.Method method) {
        SyntheticBridgePolicy.Verdict verdict = syntheticBridgePolicy.evaluate(method);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Synthetic and bridge methods cannot be targeted by a script session: " + method
                            + "; " + verdict.reason());
        }
    }

    private void enforceLimits(ScriptSessionSpec spec) {
        if (spec.ttlMillis() > limits.maxTtlMillis()) {
            throw new IllegalArgumentException("Session TTL " + spec.ttlMillis()
                    + "ms exceeds agent limit " + limits.maxTtlMillis() + "ms");
        }
        if (spec.maxHits() > limits.maxHitsCap()) {
            throw new IllegalArgumentException("Session maxHits " + spec.maxHits()
                    + " exceeds agent limit " + limits.maxHitsCap());
        }
        int total = 0;
        int perTarget = 0;
        String pendingKey = ScriptSession.targetKey(spec.target());
        for (ScriptSession existing : sessions.values()) {
            ScriptSessionStatus status = existing.status();
            if (status.terminal()) {
                continue;
            }
            total++;
            if (existing.targetKey().equals(pendingKey)) {
                perTarget++;
            }
        }
        if (total >= limits.maxTotalSessions()) {
            throw new IllegalStateException("Agent session limit reached: " + limits.maxTotalSessions()
                    + " concurrent non-terminal sessions");
        }
        if (perTarget >= limits.maxConcurrentPerTarget()) {
            throw new IllegalStateException("Target session limit reached: "
                    + limits.maxConcurrentPerTarget() + " concurrent non-terminal session(s) for "
                    + spec.target().className() + "#" + spec.target().methodName());
        }
    }

    /** Build the bounded trial rule published for an applied session. */
    private MockRule trialRule(ScriptSession session, long expireAt) {
        ScriptSessionSpec spec = session.spec();
        return baseRuleBuilder(spec, session.target(), "trial-" + spec.sessionId())
                .maxHits(spec.maxHits())
                .expireAt(expireAt)
                .build();
    }

    /** Build the unbounded formal rule published on promotion. Same profile, revision, target, script. */
    private MockRule formalRule(ScriptSession session) {
        ScriptSessionSpec spec = session.spec();
        return baseRuleBuilder(spec, session.target(), "promoted-" + spec.sessionId())
                .maxHits(0L)
                .expireAt(0L)
                .build();
    }

    private static MockRule.Builder baseRuleBuilder(ScriptSessionSpec spec, ScriptSessionTarget target,
                                                    String name) {
        return MockRule.builder()
                .id(spec.sessionId())
                .name(name)
                .target(spec.target())
                .phase(InvokePhase.BEFORE)
                .script(spec.script())
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .capabilityProfile(spec.capabilityProfile())
                .policyRevision(spec.policyRevision())
                .consecutiveFailureThreshold(3)
                .scriptSessionSource(spec.sessionId());
    }

    private static ScriptDiagnostic diagnostic(ScriptDiagnostic.Phase phase, String code, String message,
                                               ScriptSession session, String suggestion) {
        String loaderId = session.spec().target().classLoaderId();
        return new ScriptDiagnostic(phase, ScriptDiagnostic.Severity.ERROR, 0, 0, code,
                message, loaderId, suggestion);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }
}
