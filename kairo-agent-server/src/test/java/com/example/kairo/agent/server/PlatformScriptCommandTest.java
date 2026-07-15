package com.example.kairo.agent.server;

import com.example.bytecode.SampleService;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.api.ScriptSessionStatus;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-JVM exercise of the six {@code SCRIPT_*} platform commands through the poller's
 * {@code execute} entry point &mdash; the same path the polling loop drives in production. This
 * closes the V1.2 phase 5 loop: the platform dispatches a command, the agent's
 * {@link PlatformCommandPoller} hands it to the {@link com.example.kairo.agent.core.script.ScriptSessionManager}
 * against a live instrumented method, and the structured ack is reconciled by the platform.
 *
 * <p>No HTTP, no token, no platform exchange: {@code execute} is the unit the poller applies to a
 * polled command, so driving it directly is the faithful real-JVM contract.
 */
class PlatformScriptCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AgentRuntime runtime;
    private PlatformCommandPoller poller;
    private Method target;
    private String classLoaderId;
    private String methodDescriptor;

    @BeforeEach
    void setUp() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        poller = new PlatformCommandPoller(runtime, AgentLaunchConfig.parse(""), () -> { });
        target = SampleService.class.getMethod("compute", int.class);
        classLoaderId = ClassLoaderIdentity.idOf(target.getDeclaringClass().getClassLoader());
        methodDescriptor = MethodDescriptor.of(target);
    }

    @AfterEach
    void tearDown() {
        if (poller != null) {
            poller.close();
        }
        runtime.close();
    }

    @Test
    void fullLifecycleCreateValidateApplyPromoteRevert() {
        String sessionId = "poll-trial";
        Map<String, Object> created = execute(sessionCreatePayload(sessionId, "return mock.returnValue(42)",
                CapabilityProfile.SAFE, 60_000L, 10L));
        assertThat(status(created)).isEqualTo(ScriptSessionStatus.CREATED.name());
        // compute() is untouched until apply: the trial rule is not live in CREATED.
        assertThat(new SampleService().compute(7)).isEqualTo(14);

        Map<String, Object> validated = execute(commandPayload("SCRIPT_SESSION_VALIDATE", sessionId));
        assertThat(status(validated)).isEqualTo(ScriptSessionStatus.VALIDATED.name());

        Map<String, Object> applied = execute(commandPayload("SCRIPT_SESSION_APPLY", sessionId));
        assertThat(status(applied)).isEqualTo(ScriptSessionStatus.APPLIED.name());
        // The trial rule is now live on the real JVM: compute(7)=14 is replaced by 42.
        assertThat(new SampleService().compute(7)).isEqualTo(42);
        assertThat(((Number) applied.get("hitCount")).longValue()).isEqualTo(0L);

        // Promote: the trial becomes a formal rule under the same id; the session becomes REVERTED.
        Map<String, Object> promoted = execute(commandPayload("SCRIPT_SESSION_PROMOTE", sessionId));
        assertThat(status(promoted)).isEqualTo(ScriptSessionStatus.REVERTED.name());
        // The formal rule keeps intercepting after promotion.
        assertThat(new SampleService().compute(7)).isEqualTo(42);

        // Revert is idempotent on the terminal (promoted) session and must NOT delete the formal rule.
        Map<String, Object> reverted = execute(commandPayload("SCRIPT_SESSION_REVERT", sessionId));
        assertThat(status(reverted)).isEqualTo(ScriptSessionStatus.REVERTED.name());
        assertThat(new SampleService().compute(7)).isEqualTo(42);

        // Clean up the formal rule so it does not leak into other tests on the same class loader.
        runtime.remove(sessionId, "test");
        assertThat(new SampleService().compute(7)).isEqualTo(14);
    }

    @Test
    void revertRemovesTrialRuleAndRestoresOriginalBehavior() {
        String sessionId = "poll-revert";
        execute(sessionCreatePayload(sessionId, "return mock.returnValue(42)",
                CapabilityProfile.SAFE, 60_000L, 10L));
        execute(commandPayload("SCRIPT_SESSION_VALIDATE", sessionId));
        execute(commandPayload("SCRIPT_SESSION_APPLY", sessionId));
        assertThat(new SampleService().compute(7)).isEqualTo(42);

        Map<String, Object> reverted = execute(commandPayload("SCRIPT_SESSION_REVERT", sessionId));
        assertThat(status(reverted)).isEqualTo(ScriptSessionStatus.REVERTED.name());
        // The trial rule is removed; the original behavior is restored.
        assertThat(new SampleService().compute(7)).isEqualTo(14);
    }

    @Test
    void compileReturnsSuccessAndGroovyVersion() {
        Map<String, Object> result = execute(compilePayload("return 1 + 1", CapabilityProfile.SAFE));
        assertThat(result.get("successful")).isEqualTo(true);
        assertThat(String.valueOf(result.get("compilerVersion"))).startsWith("groovy-");
        assertThat(result.get("diagnostics")).isEqualTo(java.util.List.of());
    }

    @Test
    void compileFailureIsStructuredAsDiagnostic() {
        Map<String, Object> result = execute(compilePayload("return mock.proceed(", CapabilityProfile.SAFE));
        assertThat(result.get("successful")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> diagnostics = (java.util.List<Map<String, Object>>) result.get("diagnostics");
        assertThat(diagnostics).hasSize(1);
        Map<String, Object> diagnostic = diagnostics.get(0);
        assertThat(diagnostic.get("phase")).isEqualTo("COMPILATION");
        assertThat(diagnostic.get("severity")).isEqualTo("ERROR");
        assertThat(String.valueOf(diagnostic.get("code"))).isIn("SCRIPT_COMPILE_ERROR", "FORBIDDEN_SCRIPT");
        assertThat(diagnostic.get("targetClassLoaderId")).isEqualTo(classLoaderId);
    }

    @Test
    void safeCompileRejectsForbiddenApi() {
        // SAFE enforces the security blacklist: a script touching the filesystem cannot compile.
        Map<String, Object> result = execute(compilePayload(
                "new java.io.File('/tmp/kairo').exists()", CapabilityProfile.SAFE));
        assertThat(result.get("successful")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> diagnostics = (java.util.List<Map<String, Object>>) result.get("diagnostics");
        assertThat(diagnostics).hasSize(1);
        assertThat(String.valueOf(diagnostics.get(0).get("code"))).isEqualTo("FORBIDDEN_SCRIPT");
    }

    @Test
    void unrestrictedCompileAllowsIoAndReflection() {
        // UNRESTRICTED drops the AST blacklist: IO, reflection and threads all compile.
        Map<String, Object> result = execute(compilePayload(
                "Class.forName('java.lang.String').getDeclaredMethod('length')", CapabilityProfile.UNRESTRICTED));
        assertThat(result.get("successful")).isEqualTo(true);
        assertThat(result.get("diagnostics")).isEqualTo(java.util.List.of());
    }

    @Test
    void compileAgainstUnknownClassLoaderFailsClearly() {
        Map<String, Object> payload = baseCompilePayload("return 1", CapabilityProfile.SAFE);
        payload.put("targetClassLoaderId", "no-such-loader-xyz");
        assertThatThrownBy(() -> execute(payload))
                .hasMessageContaining("Target ClassLoader not found on agent");
    }

    @Test
    void trialSessionExpiresByTtlThroughPoller() throws Exception {
        String sessionId = "poll-ttl";
        execute(sessionCreatePayload(sessionId, "return mock.returnValue(42)",
                CapabilityProfile.SAFE, 1_000L, 10L));
        execute(commandPayload("SCRIPT_SESSION_VALIDATE", sessionId));
        execute(commandPayload("SCRIPT_SESSION_APPLY", sessionId));
        assertThat(new SampleService().compute(7)).isEqualTo(42);

        // The agent's local deadline drives expiry; the poller drove create/validate/apply, and the
        // manager's local TTL sweep (independent of any platform/client) is what expires it. Poll the
        // read-only snapshot until terminal, then confirm the trial rule is gone.
        String terminal = waitForTerminal(sessionId, 5_000L);
        assertThat(terminal).isEqualTo(ScriptSessionStatus.EXPIRED.name());
        assertThat(new SampleService().compute(7)).isEqualTo(14);
    }

    @Test
    void unsupportedScriptActionIsRejected() {
        // V1.6 §5.2: an unadvertised command yields a structured CAPABILITY_NOT_SUPPORTED
        // failure (not a generic "unsupported" crash), so the platform can degrade gracefully.
        assertThatThrownBy(() -> execute(commandPayload("SCRIPT_SESSION_UNKNOWN", "nope")))
                .isInstanceOf(com.example.kairo.agent.server.protocol.CapabilityNotSupportedException.class)
                .hasMessageContaining("does not advertise capability");
    }

    // -------------------------------------------------------- helpers

    private Map<String, Object> execute(Map<String, Object> payload) {
        String type = payload.get("commandType") == null
                ? "SCRIPT_SESSION_CREATE" : String.valueOf(payload.get("commandType"));
        return poller.execute(command(type, payload));
    }

    private Map<String, Object> sessionCreatePayload(String sessionId, String script,
                                                     CapabilityProfile profile, long ttl, long maxHits) {
        Map<String, Object> payload = commandPayload("SCRIPT_SESSION_CREATE", sessionId);
        payload.put("agentId", "agent-1");
        Map<String, Object> targetMap = new LinkedHashMap<>();
        targetMap.put("className", SampleService.class.getName());
        targetMap.put("classLoaderId", classLoaderId);
        targetMap.put("methodName", target.getName());
        targetMap.put("methodDescriptor", methodDescriptor);
        payload.put("target", targetMap);
        payload.put("script", script);
        payload.put("capabilityProfile", profile.name());
        payload.put("policyRevision", Map.of("revision", 1L, "hash", "test"));
        payload.put("ttlMillis", ttl);
        payload.put("maxHits", maxHits);
        payload.put("requestedBy", "tester");
        return payload;
    }

    private Map<String, Object> commandPayload(String commandType, String sessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", commandType);
        payload.put("sessionId", sessionId);
        payload.put("agentId", "agent-1");
        return payload;
    }

    private Map<String, Object> compilePayload(String script, CapabilityProfile profile) {
        Map<String, Object> payload = baseCompilePayload(script, profile);
        payload.put("targetClassLoaderId", classLoaderId);
        return payload;
    }

    private Map<String, Object> baseCompilePayload(String script, CapabilityProfile profile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", "SCRIPT_COMPILE");
        payload.put("script", script);
        payload.put("capabilityProfile", profile.name());
        payload.put("policyRevision", Map.of("revision", 1L, "hash", "test"));
        return payload;
    }

    private JsonNode command(String type, Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>(values);
        payload.put("commandType", type);
        return mapper.valueToTree(Map.of("payload", payload));
    }

    private static String status(Map<String, Object> result) {
        return String.valueOf(result.get("status"));
    }

    private String waitForTerminal(String sessionId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String status = runtime.scriptSessionManager().result(sessionId).status().name();
            if (ScriptSessionStatus.valueOf(status).terminal()) {
                return status;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("session " + sessionId + " did not reach terminal state within "
                + timeoutMillis + "ms");
    }
}
