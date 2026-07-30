package com.example.kairo.perf.leak;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression for the Codex gap-1 finding: the post-close window must be measured against
 * the <em>real closed</em> {@link AgentRuntime}, never a {@code null} that fabricates
 * zeros for every repository/registry. The previous code passed {@code null} to
 * {@code observe("post-close", ..., null)}, so {@code snapshot-cleared-on-close} and
 * related cleanup evidence passed without ever observing the closed runtime.
 *
 * <p>This test proves the observation is real by exhibiting a post-close value that is
 * <b>non-zero</b> (the transformation journal is not cleared by close) and matching the
 * direct measurement of the closed runtime, which a null-fabricated zero could never do.
 */
class LeakPostCloseEvidenceTest {

    @Test
    void postCloseObservesRealClosedRuntimeNotFabricatedZeros() throws Exception {
        try (LeakFixtureCompiler fixtures = LeakFixtureCompiler.compile()) {
            ResourceProbe probe = new ResourceProbe();
            AgentRuntime runtime = new AgentRuntime(ByteBuddyAgent.install());
            runtime.start();
            URLClassLoader loader = newUrlLoader(fixtures);
            try {
                Class<?> clazz = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
                Method echo = clazz.getMethod("echo", String.class);
                Object inst = clazz.getDeclaredConstructor().newInstance();
                runtime.publish(echo, ruleFor("pc-1", clazz, echo, loader, "return mock.returnValue('PC')"), "pc");
                assertThat(echo.invoke(inst, "x")).isEqualTo("PC");
                runtime.remove("pc-1", "pc");
            } finally {
                loader.close();
            }
            // A retransform was journaled on publish; close() does NOT clear the journal, so the
            // real post-close journal count is strictly positive. A null-fabricated zero would not be.
            int journalBeforeClose = runtime.transformationJournal().recordCount();
            assertThat(journalBeforeClose)
                    .as("publish retransformed and journaled a record").isGreaterThan(0);

            runtime.close();
            probe.boundedGc(4, 60L);

            LeakObservation postClose = probe.observe("post-close", true, runtime);
            // Real measurement: close clears the snapshot repository (0), but the journal stays at
            // its real post-close count (not cleared by close) and the observation matches the direct
            // read of the closed runtime - which a null-fabricated zero could never satisfy.
            assertThat(postClose.snapshotCount())
                    .as("close clears the snapshot repository (real zero)").isZero();
            int directJournal = runtime.transformationJournal().recordCount();
            assertThat(directJournal)
                    .as("journal retained real records after close (not cleared)").isGreaterThan(0);
            assertThat(postClose.journalRecordCount())
                    .as("post-close journal is the real closed-runtime count, not a fabricated zero")
                    .isEqualTo(directJournal)
                    .isGreaterThan(0);
            // Rules cleared on close (real, measured), not a null zero.
            assertThat(postClose.publishedRuleCount())
                    .as("rules cleared on close").isZero();
        }
    }

    @Test
    void observeRefusesNullRuntimeAndCannotFabricateZeros() {
        ResourceProbe probe = new ResourceProbe();
        // The fabrication path must be closed: a null runtime is refused, not turned into zeros.
        assertThatThrownBy(() -> probe.observe("post-close", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-null");
    }

    // -------------------------------------------------------- helpers

    private static URLClassLoader newUrlLoader(LeakFixtureCompiler fixtures) throws Exception {
        return new URLClassLoader(new URL[]{fixtures.directory().toUri().toURL()},
                ClassLoader.getSystemClassLoader());
    }

    private static MockRule ruleFor(String id, Class<?> clazz, Method method, URLClassLoader loader, String script) {
        return MockRule.builder()
                .id(id).name(id)
                .target(MethodSelector.builder()
                        .className(clazz.getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(loader))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .location(EnhancementLocation.METHOD_RETURN)
                .phase(InvokePhase.BEFORE)
                .priority(100).percentage(100).script(script)
                .scriptHash(Integer.toHexString(script.hashCode()))
                .failOpen(true).enabled(true).build();
    }
}
