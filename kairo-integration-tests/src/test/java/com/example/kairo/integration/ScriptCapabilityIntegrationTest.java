package com.example.kairo.integration;

import com.example.demo.OrderService;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.api.ScriptSessionSpec;
import com.example.kairo.api.ScriptSessionStatus;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.2 acceptance evidence (roadmap §5/§6) for the three capability tiers against a real
 * instrumented JVM: the capability matrix, ClassLoader-isolated business-DTO compile/execute,
 * legacy V1.0 rule compatibility, fail-open safety, and Platform-independent TTL unload.
 *
 * <p>These complement the compile-level {@code ScriptCapabilityPolicyTest} by proving the tiers
 * hold end-to-end through {@link AgentRuntime}: a script is compiled under the rule's tier,
 * woven into the live method, and either executes or fails open without breaking the original.
 */
class ScriptCapabilityIntegrationTest {

    private AgentRuntime runtime;
    private static final ScriptPolicyRevision REVISION = new ScriptPolicyRevision(1, "acceptance");

    @BeforeEach
    void setUp() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void safeTierAllowsOrdinaryScriptAndForbidsIo() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        // An ordinary SAFE script compiles, is woven, and overrides the return value.
        runtime.publish(method, tieredRule("safe-ok", method, InvokePhase.BEFORE,
                "return mock.returnValue(7)", CapabilityProfile.SAFE));
        assertThat(new OrderService().calculateScore(10)).isEqualTo(7);

        // A SAFE-forbidden capability (filesystem) is rejected at compile time: publishing the
        // rule throws, and no rule is registered against the target.
        assertThatThrownBy(() -> runtime.publish(method, tieredRule("safe-forbidden", method,
                InvokePhase.BEFORE, "new java.io.File('/tmp/kairo-safe').exists()",
                CapabilityProfile.SAFE)))
                .isInstanceOf(RuntimeException.class);
        // The original safe-ok rule is still the only one live; the forbidden rule never landed.
        assertThat(new OrderService().calculateScore(10)).isEqualTo(7);
    }

    @Test
    void unrestrictedTierExecutesIoReflectionAndThread() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        // UNRESTRICTED drops the AST blacklist: IO (File), reflection (getDeclaredMethod+invoke)
        // and a background thread all execute against the live method and shape its return value.
        runtime.publish(method, tieredRule("unrestricted", method, InvokePhase.BEFORE, """
                def f = new java.io.File('/tmp/kairo-unrestricted')
                def len = String.class.getDeclaredMethod('length').invoke('hello')
                def box = new java.util.concurrent.atomic.AtomicInteger(0)
                def t = new Thread({ box.set(99) })
                t.start()
                t.join()
                return mock.returnValue(((Number) len).intValue() + box.get())
                """, CapabilityProfile.UNRESTRICTED));
        // 'hello'.length() == 5, plus the thread-set 99 -> 104, overriding calculateScore(10)=20.
        assertThat(new OrderService().calculateScore(10)).isEqualTo(104);
    }

    @Test
    void extendedTierAllowsBaselineJavaAndForbidsIo() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        // EXTENDED keeps the SAFE sensitive hard-bottom but widens the allow-list to baseline
        // java.* types. A script using only java.util math compiles and runs.
        runtime.publish(method, tieredRule("extended-ok", method, InvokePhase.BEFORE, """
                def v = new java.util.ArrayList()
                v.add(3)
                v.add(4)
                return mock.returnValue(((Number) v.get(0)).intValue() + ((Number) v.get(1)).intValue())
                """, CapabilityProfile.EXTENDED));
        assertThat(new OrderService().calculateScore(10)).isEqualTo(7);

        // The sensitive hard-bottom still rejects the filesystem under EXTENDED.
        assertThatThrownBy(() -> runtime.publish(method, tieredRule("extended-forbidden", method,
                InvokePhase.BEFORE, "new java.io.File('/tmp/kairo-extended').exists()",
                CapabilityProfile.EXTENDED)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void classLoaderIsolatedBusinessDtoCompilesAndExecutes() throws Exception {
        // Compile a private business type into its own ClassLoader so it is invisible to the
        // agent ClassLoader, then target its method with a script session that references it.
        ClassLoader businessLoader = compileBusinessClass("biz.BizScore", """
                package biz;
                public class BizScore {
                    public int score(int base) { return base * 3; }
                    public static int bonus() { return 100; }
                }
                """);
        Class<?> bizScore = Class.forName("biz.BizScore", true, businessLoader);
        Method score = bizScore.getMethod("score", int.class);
        Object service = bizScore.getDeclaredConstructor().newInstance();

        ScriptSessionSpec spec = new ScriptSessionSpec("biz-session", "agent-1",
                new MethodSelector(bizScore.getName(),
                        ClassLoaderIdentity.idOf(bizScore.getClassLoader()),
                        score.getName(), MethodDescriptor.of(score)),
                // Reference the business type both as a constructor and a static call. We avoid
                // calling score() itself (the intercepted method) so the reentry guard is not hit.
                "def b = new biz.BizScore()\nreturn mock.returnValue(biz.BizScore.bonus() + args[0])",
                CapabilityProfile.UNRESTRICTED, REVISION, 60_000L, 10L, "tester");
        runtime.scriptSessionManager().create(spec);
        runtime.scriptSessionManager().validate("biz-session");
        runtime.scriptSessionManager().apply("biz-session");

        // The script referenced the business type (visible only to the target loader) and ran
        // against the real instrumented method: bonus()=100 + args[0]=5 -> 105.
        int result = (int) score.invoke(service, 5);
        assertThat(result).isEqualTo(105);

        // The business type is provably invisible to the agent ClassLoader, so the script could
        // only have been compiled against the target method's ClassLoader.
        assertThatThrownBy(() -> Class.forName("biz.BizScore", false,
                AgentRuntime.class.getClassLoader())).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void legacyRuleWithoutProfileDefaultsToSafe() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        // A V1.0 rule built without capabilityProfile or policyRevision (the legacy rule() path).
        MockRule legacy = MockRule.builder()
                .id("legacy-rule")
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .phase(InvokePhase.BEFORE)
                .script("return mock.returnValue(1)")
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
        assertThat(legacy.capabilityProfile()).isEqualTo(CapabilityProfile.SAFE);
        assertThat(legacy.policyRevision()).isNull();

        // The legacy rule compiles and runs under the implicit SAFE tier.
        runtime.publish(method, legacy);
        assertThat(new OrderService().calculateScore(10)).isEqualTo(1);

        // A legacy forbidden script is rejected under the implicit SAFE tier, proving the default
        // is enforced (not silently UNRESTRICTED).
        MockRule legacyForbidden = legacy.toBuilder().id("legacy-forbidden")
                .script("new java.io.File('/tmp/kairo-legacy')").build();
        assertThatThrownBy(() -> runtime.publish(method, legacyForbidden))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void scriptFailureInUnrestrictedTierFailsOpenWithoutBreakingOriginal() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.publish(method, tieredRule("throwing", method, InvokePhase.BEFORE, """
                throw new IllegalStateException('boom')
                """, CapabilityProfile.UNRESTRICTED));
        // The throwing script fails open: the original method runs and returns 10*2.
        assertThat(new OrderService().calculateScore(10)).isEqualTo(20);
    }

    @Test
    void trialSessionExpiresWithoutPlatformConnectivity() throws Exception {
        // §6 evidence: a trial session unloads on its local deadline with no Platform or client
        // connected. The integration runtime has no Platform poller at all, so expiry is driven
        // purely by the agent's local clock sweep.
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ScriptSessionSpec spec = new ScriptSessionSpec("offline-ttl", "agent-1",
                new MethodSelector(method.getDeclaringClass().getName(),
                        ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                        method.getName(), MethodDescriptor.of(method)),
                "return mock.returnValue(999)", CapabilityProfile.SAFE,
                REVISION, 1_000L, 10L, "tester");
        runtime.scriptSessionManager().create(spec);
        runtime.scriptSessionManager().validate("offline-ttl");
        runtime.scriptSessionManager().apply("offline-ttl");
        assertThat(new OrderService().calculateScore(7)).isEqualTo(999);

        ScriptSessionResult terminal = waitForTerminal("offline-ttl", 5_000L);
        assertThat(terminal.status()).isEqualTo(ScriptSessionStatus.EXPIRED);
        // The trial rule is removed; the original behavior is restored without any Platform action.
        assertThat(new OrderService().calculateScore(7)).isEqualTo(14);
    }

    // -------------------------------------------------------- helpers

    private static MockRule tieredRule(String id, Method method, InvokePhase phase,
                                       String script, CapabilityProfile profile) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .phase(phase)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .capabilityProfile(profile)
                .policyRevision(REVISION)
                .build();
    }

    private ScriptSessionResult waitForTerminal(String sessionId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        ScriptSessionResult result = runtime.scriptSessionManager().result(sessionId);
        while (System.currentTimeMillis() < deadline && !result.status().terminal()) {
            Thread.sleep(50L);
            result = runtime.scriptSessionManager().result(sessionId);
        }
        return result;
    }

    private static ClassLoader compileBusinessClass(String fqcn, String source) throws Exception {
        Path tmp = Files.createTempDirectory("kairo-acceptance-");
        Path src = tmp.resolve(fqcn.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(null, null, null, "-d", tmp.toString(), src.toString())).isZero();
        return new URLClassLoader(new URL[]{ tmp.toUri().toURL() },
                ClassLoader.getSystemClassLoader());
    }
}
