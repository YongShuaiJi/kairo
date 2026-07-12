package com.example.kairo.agent.core.script;

import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.ScriptDiagnostic;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.api.ScriptSessionSpec;
import com.example.kairo.api.ScriptSessionStatus;
import com.example.kairo.core.CompiledRule;

import java.util.List;

/**
 * Mutable, thread-local state for one temporary script session. All status, diagnostic and
 * applied-rule transitions are guarded by the session instance lock; {@link ScriptSessionManager}
 * holds that lock for every transition and every snapshot read, so a snapshot returned to a caller
 * is internally consistent.
 *
 * <p>The session is the agent's independent record of the deadline. Expiry is decided from
 * {@link #expiresAt} and the applied rule's hit count alone; nothing about Platform or client
 * connectivity participates, so a session expires identically whether the control plane is online,
 * offline or the client that created it has gone away.
 */
final class ScriptSession {

    private final ScriptSessionSpec spec;
    private final ScriptSessionTarget target;
    private final long createdAt;
    private final long expiresAt;

    private volatile ScriptSessionStatus status = ScriptSessionStatus.CREATED;
    private volatile List<ScriptDiagnostic> diagnostics = List.of();
    private volatile CompiledRule appliedRule;

    ScriptSession(ScriptSessionSpec spec, ScriptSessionTarget target, long createdAt, long expiresAt) {
        this.spec = spec;
        this.target = target;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    ScriptSessionSpec spec() {
        return spec;
    }

    ScriptSessionTarget target() {
        return target;
    }

    long createdAt() {
        return createdAt;
    }

    long expiresAt() {
        return expiresAt;
    }

    /** Key identifying the target method, for per-target concurrency limits. */
    String targetKey() {
        return targetKey(spec.target());
    }

    /**
     * Canonical per-target key: {@code loaderId|className#methodName descriptor}. Computed from
     * the {@link MethodSelector} so a pending session and an existing session derive the same key
     * without resolving a live class.
     */
    static String targetKey(MethodSelector target) {
        String loader = target.classLoaderId();
        String classId = (loader == null || loader.isBlank() ? "bootstrap" : loader)
                + "|" + target.className();
        return classId + "#" + target.methodName() + target.methodDescriptor();
    }

    synchronized ScriptSessionStatus status() {
        return status;
    }

    synchronized List<ScriptDiagnostic> diagnostics() {
        return diagnostics;
    }

    synchronized CompiledRule appliedRule() {
        return appliedRule;
    }

    /**
     * A consistent snapshot of the session for callers. Hit count is read from the applied rule
     * once the session is live, so it reflects real matched invocations.
     */
    synchronized ScriptSessionResult toResult() {
        long hits = appliedRule != null ? appliedRule.hits() : 0L;
        return new ScriptSessionResult(spec.sessionId(), status, createdAt, expiresAt, hits, diagnostics);
    }

    /**
     * Whether the session should expire at {@code now}. A live session also expires the moment its
     * hit cap is reached; every other state expires only when its deadline has passed.
     */
    synchronized boolean isExpired(long now) {
        if (status == ScriptSessionStatus.APPLIED && appliedRule != null
                && appliedRule.hits() >= spec.maxHits()) {
            return true;
        }
        return now >= expiresAt;
    }

    synchronized void markValidated(List<ScriptDiagnostic> diagnostics) {
        this.status = ScriptSessionStatus.VALIDATED;
        this.diagnostics = List.copyOf(diagnostics);
    }

    synchronized void markApplied(CompiledRule rule) {
        this.appliedRule = rule;
        this.status = ScriptSessionStatus.APPLIED;
        this.diagnostics = List.of();
    }

    synchronized void markReverted() {
        this.status = ScriptSessionStatus.REVERTED;
    }

    synchronized void markExpired() {
        this.status = ScriptSessionStatus.EXPIRED;
    }

    synchronized void markFailed(List<ScriptDiagnostic> diagnostics) {
        this.status = ScriptSessionStatus.FAILED;
        this.diagnostics = List.copyOf(diagnostics);
    }
}
