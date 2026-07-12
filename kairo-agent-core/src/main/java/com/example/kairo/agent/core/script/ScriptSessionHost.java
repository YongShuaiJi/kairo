package com.example.kairo.agent.core.script;

import com.example.kairo.api.MockRule;
import com.example.kairo.core.CompiledRule;

import java.lang.reflect.Method;

/**
 * The narrow slice of the agent runtime that {@link ScriptSessionManager} needs to publish and
 * remove trial rules and to record lifecycle events. Implemented by {@code AgentRuntime}; tests
 * supply a fake that wraps a real {@code RulePublisher} so the full compile path is exercised
 * without instrumentation.
 *
 * <p>The host publishes a trial rule exactly as a formal rule: the same instrumentation,
 * retransformation and event-recording path. What makes it a <em>trial</em> rule is the bounded
 * {@code maxHits}/{@code expireAt} the manager sets on the {@link MockRule}, plus the session
 * lifecycle that reverts it.
 */
public interface ScriptSessionHost {

    /**
     * Publish (or update) a rule for the target method and return its compiled handle. The
     * manager reads {@link CompiledRule#hits()} from the returned handle to track the session's
     * hit count and detect hit-cap exhaustion.
     */
    CompiledRule applyTrialRule(Method targetMethod, MockRule rule, String actor);

    /** Remove a previously applied trial rule, restoring the original method behavior. */
    void revertTrialRule(String ruleId, String actor);

    /** Record a session lifecycle event in the agent runtime event buffer. */
    void recordSessionEvent(String type, String actor, String sessionId, String target, String message);
}
