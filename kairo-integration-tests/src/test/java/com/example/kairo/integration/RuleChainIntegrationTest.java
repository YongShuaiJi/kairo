package com.example.kairo.integration;

import com.example.demo.OrderService;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.ForeignTransformerProbe;
import com.example.kairo.api.ApplyChainRequest;
import com.example.kairo.api.ApplyChainResult;
import com.example.kairo.api.ApplyChainStatus;
import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.RuleChainEntry;
import com.example.kairo.api.RuleChainRevision;
import com.example.kairo.api.RuleChainSpec;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.bytebuddy.jar.asm.Attribute;
import net.bytebuddy.jar.asm.ByteVector;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.4 rule-chain apply, fencing, idempotency, precise unload, coexistence and
 * stress, exercised against a real JVM. These are the &sect;6 / &sect;7 acceptance
 * evidence: fenced chain apply, one-snapshot-per-invocation is covered by
 * {@link RuleChainDispatcherTest}; this test covers the agent-side apply path
 * and the bytecode-level unload / coexistence guarantees.
 */
class RuleChainIntegrationTest {

    private AgentRuntime runtime;
    private Instrumentation instrumentation;

    @BeforeEach
    void setUp() {
        instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void applyChainActivatesRuleOnRealJvm() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule rule = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");

        ApplyChainResult result = runtime.applyRuleChain(request("cmd-1", "key-1",
                RuleChainRevision.initial(), 1L, target, List.of(rule), ChainDesiredState.ACTIVE));

        assertThat(result.status()).isEqualTo(ApplyChainStatus.APPLIED);
        assertThat(new OrderService().calculateScore(5)).isEqualTo(77);
    }

    @Test
    void contentOnlyChangeSwapsSnapshotWithoutRetransform() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule r1 = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");

        ApplyChainResult first = runtime.applyRuleChain(request("cmd-1", "key-1",
                RuleChainRevision.initial(), 1L, target, List.of(r1), ChainDesiredState.ACTIVE));
        assertThat(first.status()).isEqualTo(ApplyChainStatus.APPLIED);
        long retransformsBefore = runtime.transformerManager().retransformCount();

        // Re-apply with different script content but the same target/footprint.
        MockRule r2 = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(88)");
        ApplyChainResult second = runtime.applyRuleChain(request("cmd-2", "key-2",
                first.applied(), 2L, target, List.of(r2), ChainDesiredState.ACTIVE));

        assertThat(second.status()).isEqualTo(ApplyChainStatus.APPLIED);
        // Same footprint -> no retransformation; only the chain snapshot was swapped.
        assertThat(runtime.transformerManager().retransformCount()).isEqualTo(retransformsBefore);
        assertThat(new OrderService().calculateScore(5)).isEqualTo(88);
    }

    @Test
    void unloadRemovesRuleAndRestoresBehaviour() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule rule = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");

        ApplyChainResult applied = runtime.applyRuleChain(request("cmd-1", "key-1",
                RuleChainRevision.initial(), 1L, target, List.of(rule), ChainDesiredState.ACTIVE));
        assertThat(applied.status()).isEqualTo(ApplyChainStatus.APPLIED);
        assertThat(new OrderService().calculateScore(5)).isEqualTo(77);

        // Unload = EMPTY desired chain (not RESET_ALL).
        ApplyChainResult unloaded = runtime.applyRuleChain(request("cmd-2", "key-2",
                applied.applied(), 2L, target, List.of(), ChainDesiredState.EMPTY));
        assertThat(unloaded.status()).isEqualTo(ApplyChainStatus.APPLIED);
        assertThat(new OrderService().calculateScore(5)).isEqualTo(10);
    }

    @Test
    void staleCommandIsRejected() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule rule = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");

        ApplyChainResult applied = runtime.applyRuleChain(request("cmd-1", "key-1",
                RuleChainRevision.initial(), 1L, target, List.of(rule), ChainDesiredState.ACTIVE));
        // A late command claiming expected revision 0 (stale) must be rejected.
        ApplyChainResult stale = runtime.applyRuleChain(request("cmd-late", "key-late",
                RuleChainRevision.initial(), 2L, target, List.of(rule), ChainDesiredState.ACTIVE));

        assertThat(stale.status()).isEqualTo(ApplyChainStatus.STALE_COMMAND);
        // The actual chain is unchanged.
        assertThat(new OrderService().calculateScore(5)).isEqualTo(77);
    }

    @Test
    void duplicateIdempotencyKeyReplaysResult() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule rule = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");

        ApplyChainResult first = runtime.applyRuleChain(request("cmd-1", "key-dup",
                RuleChainRevision.initial(), 1L, target, List.of(rule), ChainDesiredState.ACTIVE));
        ApplyChainResult replay = runtime.applyRuleChain(request("cmd-2", "key-dup",
                RuleChainRevision.initial(), 1L, target, List.of(rule), ChainDesiredState.ACTIVE));

        assertThat(first.status()).isEqualTo(ApplyChainStatus.APPLIED);
        assertThat(replay.status()).isEqualTo(ApplyChainStatus.IDEMPOTENT_REPLAY);
        assertThat(replay.applied().value()).isEqualTo(first.applied().value());
    }

    @Test
    void outOfOrderLateCommandIsStale() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule r1 = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");
        MockRule r2 = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(88)");

        // Apply revision 2 first.
        ApplyChainResult rev2 = runtime.applyRuleChain(request("cmd-2", "key-2",
                RuleChainRevision.initial(), 2L, target, List.of(r2), ChainDesiredState.ACTIVE));
        assertThat(rev2.status()).isEqualTo(ApplyChainStatus.APPLIED);

        // A late command for revision 1 (expected=0, desired=1) arrives after revision 2.
        ApplyChainResult late = runtime.applyRuleChain(request("cmd-1-late", "key-1-late",
                RuleChainRevision.initial(), 1L, target, List.of(r1), ChainDesiredState.ACTIVE));
        assertThat(late.status()).isEqualTo(ApplyChainStatus.STALE_COMMAND);
        // The newer state (88) is preserved; the late command did not regress it.
        assertThat(new OrderService().calculateScore(5)).isEqualTo(88);
    }

    @Test
    void preciseUnloadOfMiddleRuleKeepsOthersEffective() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule a = rule("rule-a", method, EnhancementLocation.METHOD_RETURN, 30,
                "return mock.replaceReturnValue(ctx.result() + 1)");
        MockRule b = rule("rule-b", method, EnhancementLocation.METHOD_RETURN, 20,
                "return mock.replaceReturnValue(ctx.result() + 10)");
        MockRule c = rule("rule-c", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.replaceReturnValue(ctx.result() + 100)");

        ApplyChainResult applied = runtime.applyRuleChain(request("cmd-1", "key-1",
                RuleChainRevision.initial(), 1L, target, List.of(a, b, c), ChainDesiredState.ACTIVE));
        assertThat(applied.status()).isEqualTo(ApplyChainStatus.APPLIED);
        // calculateScore(5) = 10, then +1 (a), +10 (b), +100 (c) = 121
        assertThat(new OrderService().calculateScore(5)).isEqualTo(121);

        // Unload the middle rule only: re-apply the chain without b.
        ApplyChainResult withoutB = runtime.applyRuleChain(request("cmd-2", "key-2",
                applied.applied(), 2L, target, List.of(a, c), ChainDesiredState.ACTIVE));
        assertThat(withoutB.status()).isEqualTo(ApplyChainStatus.APPLIED);
        // 10 + 1 (a) + 100 (c) = 111; b is gone, a and c still effective.
        assertThat(new OrderService().calculateScore(5)).isEqualTo(111);
    }

    @Test
    void coexistenceUnsafeWhenForeignTransformerAheadIsNotIdempotent() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule rule = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                "return mock.returnValue(77)");

        ForeignTransformerProbe probe = new ForeignTransformerProbe() {
            @Override public String name() { return "non-idempotent-foreign"; }
            @Override public boolean installedAheadOfKairo() { return true; }
            @Override public boolean supportsRetransform() { return true; }
            @Override public boolean idempotent() { return false; }
        };
        runtime.transformerManager().registerForeignProbe(probe);
        try {
            ApplyChainResult result = runtime.applyRuleChain(request("cmd-1", "key-1",
                    RuleChainRevision.initial(), 1L, target, List.of(rule), ChainDesiredState.ACTIVE));
            assertThat(result.status()).isEqualTo(ApplyChainStatus.COEXISTENCE_UNSAFE);
        } finally {
            runtime.transformerManager().unregisterForeignProbe(probe);
        }
    }

    @Test
    void foreignMarkerSurvivesKairoUnload() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        String internal = OrderService.class.getName().replace('.', '/');
        AttributeAddingTransformer foreign = new AttributeAddingTransformer("V14ForeignMarker", internal);
        instrumentation.addTransformer(foreign, true);
        try {
            EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
            MockRule rule = rule("chain-a", method, EnhancementLocation.METHOD_RETURN, 10,
                    "return mock.returnValue(77)");

            ApplyChainResult applied = runtime.applyRuleChain(request("cmd-1", "key-1",
                    RuleChainRevision.initial(), 1L, target, List.of(rule), ChainDesiredState.ACTIVE));
            assertThat(applied.status()).isEqualTo(ApplyChainStatus.APPLIED);

            // Unload Kairo's chain. The foreign marker must still be present in the
            // applied bytes: Kairo's retransform only declares its own visitor and
            // never strips unknown advice.
            runtime.applyRuleChain(request("cmd-2", "key-2",
                    applied.applied(), 2L, target, List.of(), ChainDesiredState.EMPTY));
            byte[] appliedBytes = runtime.captureService().capture(OrderService.class).appliedBytes();
            assertThat(appliedBytes).isNotNull();
            assertThat(containsUtf8(appliedBytes, "V14ForeignMarker")).isTrue();
        } finally {
            instrumentation.removeTransformer(foreign);
        }
    }

    @Test
    void thousandCyclesOfPartialUnloadProduceStableHash() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        EnhancementTarget target = targetOf(method, EnhancementLocation.METHOD_RETURN);
        MockRule a = rule("rule-a", method, EnhancementLocation.METHOD_RETURN, 30,
                "return mock.replaceReturnValue(ctx.result() + 1)");
        MockRule b = rule("rule-b", method, EnhancementLocation.METHOD_RETURN, 20,
                "return mock.replaceReturnValue(ctx.result() + 10)");

        ApplyChainResult full = runtime.applyRuleChain(request("cmd-full-0", "key-full-0",
                RuleChainRevision.initial(), 1L, target, List.of(a, b), ChainDesiredState.ACTIVE));
        assertThat(full.status()).isEqualTo(ApplyChainStatus.APPLIED);
        String fullHash = full.actualHash();
        long rev = 1L;

        for (int i = 1; i <= 1_000; i++) {
            // Partial unload: remove b, keep a.
            ApplyChainResult partial = runtime.applyRuleChain(request("cmd-partial-" + i, "key-partial-" + i,
                    new RuleChainRevision(rev, fullHash), rev + 1, target, List.of(a), ChainDesiredState.ACTIVE));
            assertThat(partial.status()).isEqualTo(ApplyChainStatus.APPLIED);
            rev++;
            String partialHash = partial.actualHash();
            // Re-apply the full chain; hash must return to the original full hash.
            ApplyChainResult again = runtime.applyRuleChain(request("cmd-full-" + i, "key-full-" + i,
                    new RuleChainRevision(rev, partialHash), rev + 1, target, List.of(a, b), ChainDesiredState.ACTIVE));
            assertThat(again.status()).isEqualTo(ApplyChainStatus.APPLIED);
            assertThat(again.actualHash()).isEqualTo(fullHash);
            rev++;
        }
        // Final behaviour: both rules active.
        assertThat(new OrderService().calculateScore(5)).isEqualTo(21);
    }

    // -------------------------------------------------------- helpers

    private static EnhancementTarget targetOf(Method method, EnhancementLocation location) {
        MethodSelector selector = new MethodSelector(method.getDeclaringClass().getName(),
                com.example.kairo.core.ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                method.getName(), com.example.kairo.core.MethodDescriptor.of(method));
        return EnhancementTarget.of(selector, location);
    }

    private static MockRule rule(String id, Method method, EnhancementLocation location, int priority, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(com.example.kairo.core.ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(com.example.kairo.core.MethodDescriptor.of(method))
                        .build())
                .location(location)
                .phase(InvokePhase.BEFORE)
                .priority(priority)
                .script(script)
                .scriptHash(Integer.toHexString(script.hashCode()))
                .build();
    }

    private static List<RuleChainEntry> entries(List<MockRule> rules) {
        return rules.stream()
                .map(r -> RuleChainEntry.builder()
                        .ruleId(r.id())
                        .version(r.version())
                        .priority(r.priority())
                        .createdAtMillis(r.createdAt())
                        .scriptHash(r.scriptHash() == null ? "" : r.scriptHash())
                        .mutexGroup(r.mutexGroup())
                        .build())
                .toList();
    }

    private static ApplyChainRequest request(String commandId, String idempotencyKey,
                                             RuleChainRevision expected, long desiredRevision,
                                             EnhancementTarget target, List<MockRule> rules,
                                             ChainDesiredState state) {
        RuleChainSpec spec = RuleChainSpec.builder()
                .chainId(target.method().className() + "#" + target.method().methodName())
                .revision(desiredRevision)
                .target(target)
                .entries(entries(rules))
                .desiredState(state)
                .build();
        return ApplyChainRequest.builder()
                .commandId(commandId)
                .idempotencyKey(idempotencyKey)
                .expected(expected)
                .desired(spec)
                .rules(rules)
                .target(target)
                .deadlineMillis(30_000L)
                .build();
    }

    private static boolean containsUtf8(byte[] bytes, String token) {
        byte[] needle = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** A foreign transformer that appends a custom class attribute as a detectable marker. */
    private static final class AttributeAddingTransformer implements ClassFileTransformer {
        private final String attributeName;
        private final String internalName;

        AttributeAddingTransformer(String attributeName, String internalName) {
            this.attributeName = attributeName;
            this.internalName = internalName;
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (name == null || !name.equals(internalName) || classfileBuffer == null) {
                return null;
            }
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, 0);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    super.visit(version, access, name, signature, superName, interfaces);
                    visitAttribute(new MarkerAttribute(attributeName));
                }
            };
            reader.accept(visitor, 0);
            return writer.toByteArray();
        }
    }

    private static final class MarkerAttribute extends Attribute {
        MarkerAttribute(String name) {
            super(name);
        }

        @Override
        protected ByteVector write(ClassWriter cw, byte[] code, int len, int maxStack, int maxLocals) {
            ByteVector vector = new ByteVector();
            vector.putShort(cw.newUTF8(type));
            vector.putInt(0);
            return vector;
        }
    }
}
