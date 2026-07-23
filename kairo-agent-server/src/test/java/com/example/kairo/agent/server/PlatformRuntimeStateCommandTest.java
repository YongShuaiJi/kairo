package com.example.kairo.agent.server;

import com.example.bytecode.SampleService;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.ProcessStartId;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.api.snapshot.ChainSnapshot;
import com.example.kairo.api.snapshot.SnapshotBounds;
import com.example.kairo.api.snapshot.SnapshotTruncation;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-C &sect;8.3: the {@code REFRESH_RUNTIME_STATE} command as executed by the
 * {@link PlatformCommandPoller} on a real JVM. Drives the poller's {@code execute} entry point
 * (the same path the polling loop applies to a polled command), so it is the faithful real-JVM
 * contract: no HTTP, no token, no platform exchange.
 *
 * <p>Proves the poller returns a real bounded snapshot (not the placeholder {@code {refreshed:true}}),
 * that an applied rule appears in the snapshot's rules and chains, that unloading it removes it,
 * and that the snapshot is bounded and carries no prohibited sensitive/large fields.
 */
class PlatformRuntimeStateCommandTest {

    private static final String AGENT_ID = "agent-refresh-test";
    private static final String PROCESS_START_ID = "test-process-start-id";

    private final ObjectMapper mapper = new ObjectMapper();
    private AgentRuntime runtime;
    private PlatformCommandPoller poller;
    private Method target;
    private String classLoaderId;
    private String methodDescriptor;
    private String classId;

    @BeforeEach
    void setUp() throws Exception {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        poller = new PlatformCommandPoller(runtime,
                AgentLaunchConfig.parse("platformAgentId=" + AGENT_ID
                        + ",platformProcessStartId=" + PROCESS_START_ID), () -> { });
        target = SampleService.class.getMethod("compute", int.class);
        classLoaderId = ClassLoaderIdentity.idOf(target.getDeclaringClass().getClassLoader());
        methodDescriptor = MethodDescriptor.of(target);
        classId = runtime.loadedClassRepository().classId(SampleService.class);
    }

    @AfterEach
    void tearDown() {
        if (poller != null) {
            poller.close();
        }
        runtime.close();
    }

    @Test
    void refreshRuntimeStateReturnsRealSnapshotWithAppliedRule() {
        execute(applyRulePayload("snap-rule"));
        Map<String, Object> result = execute(command("REFRESH_RUNTIME_STATE", Map.of()));
        AgentRuntimeSnapshot snapshot = toSnapshot(result);

        assertThat(snapshot.protocolVersion()).isEqualTo(SnapshotBounds.PROTOCOL_VERSION);
        assertThat(snapshot.agentId()).isEqualTo(AGENT_ID);
        // The override is preserved through the centralized ProcessStartId formula (no drift).
        assertThat(snapshot.processStartId()).isEqualTo(PROCESS_START_ID);
        assertThat(snapshot.processStartId())
                .isEqualTo(ProcessStartId.resolve(PROCESS_START_ID, runtime.jvmInfo()));
        assertThat(snapshot.agentVersion()).isEqualTo(runtime.jvmInfo().agentVersion());
        assertThat(snapshot.disabled()).isFalse();
        assertThat(snapshot.observedAt()).isPositive();
        assertThat(snapshot.truncation()).isNotNull();
        assertThat(snapshot.rules()).extracting(r -> r.ruleId()).contains("snap-rule");
        // APPLY_RULE publishes to both the legacy rules store and the chain registry.
        assertThat(snapshot.chains()).extracting(ChainSnapshot::ruleIds)
                .anyMatch(ids -> ids.contains("snap-rule"));
    }

    @Test
    void unloadRemovesRuleFromSnapshot() {
        execute(applyRulePayload("unload-rule"));
        assertThat(toSnapshot(execute(command("REFRESH_RUNTIME_STATE", Map.of()))).rules())
                .extracting(r -> r.ruleId()).contains("unload-rule");

        execute(command("RESET_CLASS", Map.of("classId", classId)));
        AgentRuntimeSnapshot after = toSnapshot(execute(command("REFRESH_RUNTIME_STATE", Map.of())));
        assertThat(after.rules()).extracting(r -> r.ruleId()).doesNotContain("unload-rule");
        assertThat(after.chains()).allSatisfy(chain ->
                assertThat(chain.ruleIds()).doesNotContain("unload-rule"));
    }

    @Test
    void applyChainCarriesExactChainIdAndRuleInRulesAndChains() {
        // APPLY_CHAIN writes the authoritative RuleRegistry chain (not AgentRuntime.publishedRules).
        String chainId = "exact-chain-id-123";
        Map<String, Object> applied = execute(applyChainPayload(chainId, "chain-rule", "ACTIVE", 0, ""));
        assertThat(applied.get("status")).isEqualTo("APPLIED");
        String appliedHash = String.valueOf(applied.get("appliedHash"));
        AgentRuntimeSnapshot snapshot = toSnapshot(execute(command("REFRESH_RUNTIME_STATE", Map.of())));

        // Fix 1: the exact Platform-assigned chainId is preserved (not a partial target-derived id).
        assertThat(snapshot.chains()).extracting(ChainSnapshot::chainId).contains(chainId);
        assertThat(snapshot.chains()).extracting(ChainSnapshot::ruleIds)
                .anyMatch(ids -> ids.contains("chain-rule"));
        // Fix 2: an APPLY_CHAIN-only rule appears in rules[] (derived from chains, no second store).
        assertThat(snapshot.rules()).extracting(r -> r.ruleId()).contains("chain-rule");

        // Unload (EMPTY desired) with the correct expected revision+hash fencing removes the chain
        // and the rule from the snapshot.
        Map<String, Object> unloaded = execute(applyChainPayload(chainId, "chain-rule", "EMPTY", 1, appliedHash));
        assertThat(unloaded.get("status")).isEqualTo("APPLIED");
        AgentRuntimeSnapshot after = toSnapshot(execute(command("REFRESH_RUNTIME_STATE", Map.of())));
        assertThat(after.chains()).extracting(ChainSnapshot::chainId).doesNotContain(chainId);
        assertThat(after.rules()).extracting(r -> r.ruleId()).doesNotContain("chain-rule");
    }

    @Test
    void refreshSnapshotIsBoundedAndRedacted() throws Exception {
        execute(applyRulePayload("redact-rule"));
        Map<String, Object> result = execute(command("REFRESH_RUNTIME_STATE", Map.of()));
        AgentRuntimeSnapshot snapshot = toSnapshot(result);
        SnapshotTruncation truncation = snapshot.truncation();
        assertThat(truncation.byteLimit()).isEqualTo(SnapshotBounds.MAX_SERIALIZED_BYTES);
        assertThat(truncation.serializedBytes()).isLessThanOrEqualTo(SnapshotBounds.MAX_SERIALIZED_BYTES);

        String json = mapper.writeValueAsString(result);
        // The applied rule's script source never reaches the snapshot.
        assertThat(json).doesNotContain("mock.proceed", "mock.returnValue");
        assertThat(json).doesNotContain(
                "\"script\":", "\"scriptSource\":", "\"sourceCode\":", "\"bytecodeBase64Url\":",
                "\"bytecode\":", "\"token\":", "\"authorization\":", "\"classBytes\":",
                "\"decompiled\":", "\"decompilation\":", "\"payload_json\":", "\"result_json\":",
                "\"password\":", "\"secret\":", "\"events\":");
    }

    // -------------------------------------------------------- helpers

    private Map<String, Object> execute(JsonNode command) {
        return poller.execute(command);
    }

    private JsonNode command(String type, Map<String, Object> values) {
        Map<String, Object> payload = new LinkedHashMap<>(values);
        payload.put("commandType", type);
        return mapper.valueToTree(Map.of("payload", payload));
    }

    private JsonNode applyRulePayload(String ruleId) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", ruleId);
        rule.put("version", 1L);
        rule.put("classId", classId);
        rule.put("className", SampleService.class.getName());
        rule.put("classLoaderId", classLoaderId);
        rule.put("methodName", target.getName());
        rule.put("methodDescriptor", methodDescriptor);
        rule.put("phase", "BEFORE");
        rule.put("script", "return mock.proceed()");
        return command("APPLY_RULE", Map.of("rule", rule));
    }

    private JsonNode applyChainPayload(String chainId, String ruleId, String desiredState,
                                       long expectedRevision, String expectedHash) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", ruleId);
        rule.put("version", 1L);
        rule.put("classId", classId);
        rule.put("className", SampleService.class.getName());
        rule.put("classLoaderId", classLoaderId);
        rule.put("methodName", target.getName());
        rule.put("methodDescriptor", methodDescriptor);
        rule.put("phase", "BEFORE");
        rule.put("script", "return mock.proceed()");

        Map<String, Object> desired = new LinkedHashMap<>();
        desired.put("chainId", chainId);
        desired.put("revision", 1L);
        desired.put("desiredState", desiredState);
        desired.put("transformationRevision", 0L);

        Map<String, Object> targetNode = new LinkedHashMap<>();
        targetNode.put("className", SampleService.class.getName());
        targetNode.put("classLoaderId", classLoaderId);
        targetNode.put("methodName", target.getName());
        targetNode.put("methodDescriptor", methodDescriptor);
        targetNode.put("location", "METHOD_ENTER");

        long unique = System.nanoTime();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", "APPLY_CHAIN");
        payload.put("commandId", "chain-cmd-" + chainId + "-" + desiredState + "-" + unique);
        payload.put("idempotencyKey", "chain-key-" + chainId + "-" + desiredState + "-" + unique);
        payload.put("desiredState", desiredState);
        payload.put("rules", "EMPTY".equals(desiredState) ? List.of() : List.of(rule));
        payload.put("desired", desired);
        payload.put("target", targetNode);
        payload.put("expected", Map.of("value", expectedRevision, "hash", expectedHash));
        return mapper.valueToTree(Map.of("payload", payload));
    }

    private AgentRuntimeSnapshot toSnapshot(Map<String, Object> result) {
        return mapper.convertValue(result, AgentRuntimeSnapshot.class);
    }
}
