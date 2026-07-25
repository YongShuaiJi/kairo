package com.example.kairo.agent.core;

import example.demo.ExampleTarget;
import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.RuleChainRevision;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.api.snapshot.ChainSnapshot;
import com.example.kairo.api.snapshot.RuleSnapshot;
import com.example.kairo.api.snapshot.SnapshotBounds;
import com.example.kairo.api.snapshot.SnapshotTruncation;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.MethodDescriptor;
import com.example.kairo.core.RuleChainSnapshot;
import com.example.kairo.groovy.CompiledMockScript;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-C &sect;8.3: the Agent runtime-state snapshot. Exercises the real {@link AgentRuntime}
 * for apply/unload visibility and concurrent mutation consistency, and the stateless
 * {@link RuntimeStateSnapshotBuilder} directly for deterministic ordering, bounded-memory
 * collection (over-limit source &rarr; bounded retained output + stable prefix), the final 1 MiB
 * serialized byte enforcement, and the absence of prohibited sensitive/large fields.
 *
 * <p>Time is wall-clock (the snapshot's {@code observedAt}); concurrency uses a {@link CyclicBarrier}
 * with bounded iterations and explicit timeouts, never long sleeps.
 */
class RuntimeStateSnapshotTest {

    private static final String PROCESS_START_ID = "snapshot-host:4242:1700000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private Instrumentation instrumentation;
    private AgentRuntime runtime;
    private Method target;

    @BeforeEach
    void setUp() throws Exception {
        instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        target = ExampleTarget.class.getMethod("calculateScore", int.class);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    // -------------------------------------------------------- real AgentRuntime apply / unload

    @Test
    void applyRuleAppearsInSnapshotAndUnloadRemovesIt() {
        runtime.publish(target, rule("snap-rule", target), "test");
        AgentRuntimeSnapshot snapshot = runtime.snapshotRuntimeState("agent-snap", PROCESS_START_ID);

        assertThat(snapshot.protocolVersion()).isEqualTo(SnapshotBounds.PROTOCOL_VERSION);
        assertThat(snapshot.agentId()).isEqualTo("agent-snap");
        assertThat(snapshot.processStartId()).isEqualTo(PROCESS_START_ID);
        assertThat(snapshot.agentVersion()).isEqualTo(runtime.agentVersion());
        assertThat(snapshot.disabled()).isFalse();
        assertThat(snapshot.observedAt()).isPositive();
        // APPLY_RULE publishes to the registry chain (rules[] and chains[] both derive from it).
        assertThat(snapshot.rules()).extracting(RuleSnapshot::ruleId).contains("snap-rule");
        assertThat(snapshot.chains()).extracting(ChainSnapshot::ruleIds)
                .anyMatch(ids -> ids.contains("snap-rule"));
        RuleSnapshot rule = snapshot.rules().stream()
                .filter(r -> "snap-rule".equals(r.ruleId())).findFirst().orElseThrow();
        assertThat(rule.enabled()).isTrue();

        runtime.remove("snap-rule", "test");
        AgentRuntimeSnapshot after = runtime.snapshotRuntimeState("agent-snap", PROCESS_START_ID);
        assertThat(after.rules()).extracting(RuleSnapshot::ruleId).doesNotContain("snap-rule");
        assertThat(after.chains()).allSatisfy(chain ->
                assertThat(chain.ruleIds()).doesNotContain("snap-rule"));
    }

    @Test
    void disableAllIsReflectedAsDisabled() {
        assertThat(runtime.snapshotRuntimeState("agent-snap", PROCESS_START_ID).disabled()).isFalse();
        runtime.disableAll(true);
        assertThat(runtime.snapshotRuntimeState("agent-snap", PROCESS_START_ID).disabled()).isTrue();
        runtime.disableAll(false);
        assertThat(runtime.snapshotRuntimeState("agent-snap", PROCESS_START_ID).disabled()).isFalse();
    }

    @Test
    void concurrentMutationAndSnapshotStaysConsistent() throws Exception {
        int iterations = 30;
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> mutator = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < iterations; i++) {
                    runtime.publish(target, rule("concurrent-rule", target), "test");
                    runtime.remove("concurrent-rule", "test");
                }
                return null;
            });
            Future<?> snapshotter = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < iterations; i++) {
                    AgentRuntimeSnapshot s = runtime.snapshotRuntimeState("agent-snap", PROCESS_START_ID);
                    // Every rule entry is well-formed: the read lock serializes the snapshot against
                    // the publish/remove mutation, so no torn or partial entry is ever observed.
                    for (RuleSnapshot r : s.rules()) {
                        assertThat(r.ruleId()).isNotBlank();
                        assertThat(r.ruleVersion()).isGreaterThanOrEqualTo(0L);
                    }
                }
                return null;
            });
            mutator.get(30, TimeUnit.SECONDS);
            snapshotter.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void snapshotCarriesNoProhibitedSensitiveOrLargeFields() throws Exception {
        runtime.publish(target, rule("redact-rule", target), "test");
        AgentRuntimeSnapshot snapshot = runtime.snapshotRuntimeState("agent-redact", PROCESS_START_ID);
        String json = mapper.writeValueAsString(snapshot);
        // The applied rule's script source ("return mock.proceed()") must never reach the snapshot.
        assertThat(json).doesNotContain("mock.proceed", "mock.returnValue");
        // None of the prohibited sensitive/large field keys are present (quoted-key form avoids a
        // false positive on the legitimate "descriptor" field, which contains the substring "script").
        assertThat(json).doesNotContain(
                "\"script\":", "\"scripts\":", "\"scriptSource\":", "\"sourceCode\":",
                "\"bytecodeBase64Url\":", "\"bytecode\":", "\"token\":", "\"authorization\":",
                "\"classBytes\":", "\"decompiled\":", "\"decompilation\":", "\"payload_json\":",
                "\"result_json\":", "\"password\":", "\"secret\":", "\"events\":");
    }

    // -------------------------------------------------------- builder: ordering, limits, bytes

    @Test
    void collectionsAreStablySorted() {
        // rules[] is derived from the chain snapshots (Fix 2); each chain carries one rule.
        Map<EnhancementTarget, RuleChainSnapshot> chains = new LinkedHashMap<>();
        chains.put(chainTarget("com.test.Zeta"), chainWithRules("com.test.Zeta", 9, "rule-c"));
        chains.put(chainTarget("com.test.Alpha"), chainWithRules("com.test.Alpha", 1, "rule-a"));
        chains.put(chainTarget("com.test.Mike"), chainWithRules("com.test.Mike", 5, "rule-b"));
        Map<String, String> degraded = new LinkedHashMap<>();
        degraded.put("com.degraded.Zeta", "z");
        degraded.put("com.degraded.Alpha", "a");
        degraded.put("com.degraded.Mike", "m");

        AgentRuntimeSnapshot snapshot = build(chains, degraded);

        assertThat(snapshot.rules()).extracting(RuleSnapshot::ruleId)
                .containsExactly("rule-a", "rule-b", "rule-c");
        assertThat(snapshot.chains()).extracting(ChainSnapshot::chainId)
                .containsExactly(chainIdOf("com.test.Alpha"), chainIdOf("com.test.Mike"),
                        chainIdOf("com.test.Zeta"));
        assertThat(snapshot.chains()).extracting(ChainSnapshot::ruleIds)
                .containsExactly(List.of("rule-a"), List.of("rule-b"), List.of("rule-c"));
        assertThat(snapshot.degradedClasses()).containsExactly(
                "com.degraded.Alpha", "com.degraded.Mike", "com.degraded.Zeta");
    }

    @Test
    void overLimitSourceProducesBoundedRetainedOutputWithStablePrefix() {
        // 6000 chains (each one rule, rule-0000..rule-5999) + 2000 degraded classes. The source is
        // over every bound; the builder streams it once, retains at most MAX_RULES rules /
        // MAX_CHAINS chains / MAX_DEGRADED degraded (bounded top-K), counts the totals while
        // scanning, and the final serialized snapshot fits the byte cap. The retained rules are the
        // stable-sorted prefix (smallest ruleIds), proving bounded-memory collection.
        Map<EnhancementTarget, RuleChainSnapshot> chains = new LinkedHashMap<>();
        for (int i = 0; i < 6000; i++) {
            String id = String.format("%04d", i);
            chains.put(chainTarget("c" + id), chainWithRules("c" + id, i, "rule-" + id));
        }
        Map<String, String> degraded = new LinkedHashMap<>();
        for (int i = 0; i < 2000; i++) {
            degraded.put("com.degraded.C" + String.format("%04d", i), "reason");
        }

        AgentRuntimeSnapshot snapshot = build(chains, degraded);
        SnapshotTruncation t = snapshot.truncation();

        assertThat(t.serializedBytes()).isLessThanOrEqualTo(SnapshotBounds.MAX_SERIALIZED_BYTES);
        assertThat(t.byteLimit()).isEqualTo(SnapshotBounds.MAX_SERIALIZED_BYTES);
        // rules[] is bounded to MAX_RULES and is the stable-sorted prefix.
        assertThat(t.rules().total()).isEqualTo(6000);
        assertThat(snapshot.rules()).hasSizeLessThanOrEqualTo(SnapshotBounds.MAX_RULES);
        assertThat(snapshot.rules()).extracting(RuleSnapshot::ruleId).isSorted();
        assertThat(snapshot.rules()).extracting(RuleSnapshot::ruleId).first()
                .isEqualTo("rule-0000");
        // degraded is bounded to MAX_DEGRADED (ENTRY_COUNT_LIMIT, fits the byte cap).
        assertThat(t.degradedClasses().total()).isEqualTo(2000);
        assertThat(snapshot.degradedClasses()).hasSizeLessThanOrEqualTo(SnapshotBounds.MAX_DEGRADED_CLASSES);
        // chains is bounded to MAX_CHAINS and (byte-capped) sorted.
        assertThat(t.chains().total()).isEqualTo(6000);
        assertThat(snapshot.chains()).hasSizeLessThanOrEqualTo(SnapshotBounds.MAX_CHAINS);
        assertThat(snapshot.chains()).extracting(ChainSnapshot::chainId).isSorted();
    }

    @Test
    void chainCountBoundIsAppliedBeforeTheByteCap() {
        // 6000 chains with 64-char hashes (~250 bytes each): the count bound caps at 5000 first,
        // then the byte cap deterministically reduces further because 5000 chains exceed 1 MiB.
        // The count bound is always respected (included <= MAX_CHAINS); the byte cap is the binding
        // constraint, so the reason is SERIALIZED_BYTE_LIMIT.
        Map<EnhancementTarget, RuleChainSnapshot> chains = new LinkedHashMap<>();
        for (int i = 0; i < 6000; i++) {
            String name = "b" + String.format("%04d", i);
            chains.put(chainTarget(name), bigChain(name, i));
        }

        AgentRuntimeSnapshot snapshot = build(chains, Map.of());
        SnapshotTruncation t = snapshot.truncation();

        assertThat(t.serializedBytes()).isLessThanOrEqualTo(SnapshotBounds.MAX_SERIALIZED_BYTES);
        assertThat(t.byteLimit()).isEqualTo(SnapshotBounds.MAX_SERIALIZED_BYTES);
        assertThat(t.chains().total()).isEqualTo(6000);
        assertThat(t.chains().included()).isLessThanOrEqualTo(SnapshotBounds.MAX_CHAINS);
        assertThat(t.chains().included()).isLessThan(SnapshotBounds.MAX_CHAINS);
        assertThat(t.chains().reason()).isEqualTo(SnapshotBounds.REASON_SERIALIZED_BYTE_LIMIT);
        assertThat(snapshot.chains()).hasSize(t.chains().included());
        // The reduced set is the stable-sorted prefix (deterministic reduction from the end).
        assertThat(snapshot.chains()).extracting(ChainSnapshot::chainId)
                .startsWith(chainIdOf("b0000"));
    }

    // -------------------------------------------------------- helpers

    private static AgentRuntimeSnapshot build(Map<EnhancementTarget, RuleChainSnapshot> chains,
                                               Map<String, String> degraded) {
        return RuntimeStateSnapshotBuilder.build(
                visitor -> chains.values().forEach(visitor),
                visitor -> degraded.forEach(visitor),
                "0.1.0-SNAPSHOT", false, false, "agent", PROCESS_START_ID);
    }

    private MockRule rule(String id, Method method) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .phase(InvokePhase.BEFORE)
                .script("return mock.proceed()")
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private static CompiledRule compiledRule(String id) {
        MockRule rule = MockRule.builder()
                .id(id)
                .version(1L)
                .name(id)
                .target(new MethodSelector("c", null, "m", "()V"))
                .phase(InvokePhase.BEFORE)
                .script("return mock.proceed()")
                .capabilityProfile(CapabilityProfile.SAFE)
                .enabled(true)
                .expireAt(0L)
                .build();
        return new CompiledRule(rule, stubScript(id));
    }

    private static CompiledMockScript stubScript(String id) {
        return new CompiledMockScript() {
            @Override
            public String ruleId() {
                return id;
            }

            @Override
            public long version() {
                return 1L;
            }

            @Override
            public String scriptHash() {
                return "";
            }

            @Override
            public MockDecision execute(InvocationContext context) {
                return null;
            }
        };
    }

    private static EnhancementTarget chainTarget(String className) {
        return EnhancementTarget.of(
                new MethodSelector(className, null, "m", "()V"),
                EnhancementLocation.METHOD_ENTER);
    }

    private static String chainIdOf(String className) {
        return RuleChainSnapshot.chainIdOf(chainTarget(className));
    }

    private static RuleChainSnapshot chainWithRules(String className, long revision, String ruleId) {
        EnhancementTarget target = chainTarget(className);
        List<CompiledRule> rules = List.of(compiledRule(ruleId));
        return new RuleChainSnapshot(
                new RuleChainRevision(revision, "h" + revision),
                RuleChainSnapshot.chainIdOf(target),
                "h" + revision, rules, target, 0L, "t" + revision, 0L, null);
    }

    private static RuleChainSnapshot bigChain(String className, long revision) {
        EnhancementTarget target = chainTarget(className);
        String hash = "hash" + String.format("%060d", revision);
        return new RuleChainSnapshot(
                new RuleChainRevision(revision, hash),
                RuleChainSnapshot.chainIdOf(target),
                hash, List.of(), target, revision, hash, 0L, null);
    }
}
