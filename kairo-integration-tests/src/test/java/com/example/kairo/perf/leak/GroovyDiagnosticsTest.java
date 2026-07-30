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
 * Focused tests for {@link GroovyCompilerDiagnostics}: it must read the <em>real</em>
 * {@code GroovyScriptCompiler} cache / generations / {@code KairoGroovyClassLoader}
 * instances via reflection, and it must be fail-closed (a null runtime or an
 * unreflectable layout throws, never returns a fabricated zero that would let a gate pass).
 *
 * <p>These run a real {@link AgentRuntime} with a real Groovy compile (no full budget
 * gate) so the reflection path is exercised against the actual production layout.
 */
class GroovyDiagnosticsTest {

    @Test
    void measuresRealCacheAndGenerationsAfterCompile() throws Exception {
        try (LeakFixtureCompiler fixtures = LeakFixtureCompiler.compile()) {
            AgentRuntime runtime = new AgentRuntime(ByteBuddyAgent.install());
            runtime.start();
            try {
                // Before any compile: the compiler is real but empty (measured, not fabricated).
                GroovyCompilerDiagnostics.GroovyDiagnostics before = GroovyCompilerDiagnostics.measure(runtime);
                assertThat(before.cacheEntries()).isZero();
                assertThat(before.generationCount()).isZero();
                assertThat(before.maxClassesInGeneration()).isZero();
                assertThat(before.liveGroovyLoaders()).isEmpty();

                URLClassLoader loader = newUrlLoader(fixtures);
                Class<?> clazz = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
                Method echo = clazz.getMethod("echo", String.class);
                runtime.publish(echo, ruleFor("gd-1", clazz, echo, loader, "return mock.returnValue('GD')"), "gd");

                // After a compile: the cache, a generation and a KairoGroovyClassLoader exist.
                GroovyCompilerDiagnostics.GroovyDiagnostics after = GroovyCompilerDiagnostics.measure(runtime);
                assertThat(after.cacheEntries()).as("cache populated by compile").isGreaterThanOrEqualTo(1);
                assertThat(after.generationCount()).as("generation created by compile").isGreaterThanOrEqualTo(1);
                assertThat(after.maxClassesInGeneration()).as("at least one class in the generation").isGreaterThanOrEqualTo(1);
                assertThat(after.liveGroovyLoaders()).as("a real KairoGroovyClassLoader is held by the generation")
                        .hasSizeGreaterThanOrEqualTo(1);
                // The discovered Groovy loader is the defining loader of a compiled Groovy class.
                ClassLoader groovyLoader = after.liveGroovyLoaders().get(0);
                assertThat(groovyLoader).isNotNull();

                runtime.remove("gd-1", "gd");
                loader.close();
            } finally {
                runtime.close();
            }
            // After close: compiler.close() cleared the cache and generations (real zeros, measured).
            GroovyCompilerDiagnostics.GroovyDiagnostics closed = GroovyCompilerDiagnostics.measure(runtime);
            assertThat(closed.cacheEntries()).as("cache cleared on close").isZero();
            assertThat(closed.generationCount()).as("generations cleared on close").isZero();
            assertThat(closed.liveGroovyLoaders()).isEmpty();
        }
    }

    @Test
    void measureNullRuntimeIsFailClosed() {
        // A null runtime can never yield real Groovy state; the diagnostics must throw rather
        // than fabricate zeros that would let a gate pass.
        assertThatThrownBy(() -> GroovyCompilerDiagnostics.measure(null))
                .isInstanceOf(GroovyCompilerDiagnostics.GroovyDiagnosticUnavailableException.class);
    }

    @Test
    void generationHighWaterCapturesRealCompilationAcrossGcAndClose() throws Exception {
        // The run-scoped high-water is folded from every successful GroovyCompilerDiagnostics.measure
        // call (registerGroovyLoaders in a cycle's finally + each observation). Real compilation
        // makes it strictly positive and bounded by MAX_CLASSES_PER_GENERATION; it survives a
        // bounded GC and compiler.close() (which collapse the point-in-time
        // maxClassesInGeneration to 0), proving the generation-class gate no longer reports a
        // fabricated zero. Reflection failure stays fail-closed (measure throws, high-water
        // is never updated from a fabricated value).
        try (LeakFixtureCompiler fixtures = LeakFixtureCompiler.compile()) {
            ResourceProbe probe = new ResourceProbe();
            AgentRuntime runtime = new AgentRuntime(ByteBuddyAgent.install());
            runtime.start();
            LeakObservation after;
            URLClassLoader loader = newUrlLoader(fixtures);
            try {
                Class<?> clazz = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
                Method echo = clazz.getMethod("echo", String.class);
                Object inst = clazz.getDeclaredConstructor().newInstance();
                runtime.publish(echo, ruleFor("hw-1", clazz, echo, loader, "return mock.returnValue('HW')"), "hw");
                assertThat(echo.invoke(inst, "x")).isEqualTo("HW");
                runtime.remove("hw-1", "hw");

                // Mirror the harness cycle: registerGroovyLoaders measures the live generation
                // BEFORE any GC and folds its classesInGeneration into the run high-water.
                probe.registerGroovyLoaders(runtime, ResourceProbe.LoaderPhase.MEASURED);

                // A post-full-GC observation: the weakly-held generation holder may now be
                // cleared, so the point-in-time maxClassesInGeneration can be 0, but the
                // high-water is the real, run-scoped peak.
                after = probe.observe("after-compile", true, runtime);
                assertThat(after.groovy().generationHighWater())
                        .as("real compilation makes the observed high-water strictly positive")
                        .isGreaterThan(0)
                        .as("bounded by the product MAX_CLASSES_PER_GENERATION")
                        .isLessThanOrEqualTo(LeakBudget.DOCUMENTED.generationMaxClasses());
                assertThat(probe.generationHighWater())
                        .as("the probe's run-scoped counter matches the observation")
                        .isEqualTo(after.groovy().generationHighWater());
            } finally {
                loader.close();
                runtime.close();
            }
            // After close: compiler.close() cleared the cache and generations, so the
            // point-in-time max is 0, but the run high-water is preserved (the probe counter
            // is never reset by close or GC).
            LeakObservation postClose = probe.observe("post-close", true, runtime);
            assertThat(postClose.groovy().maxClassesInGeneration())
                    .as("point-in-time max collapses to 0 after close + GC").isZero();
            assertThat(postClose.groovy().generationHighWater())
                    .as("the run high-water survives close (not a fabricated zero)")
                    .isEqualTo(after.groovy().generationHighWater())
                    .isGreaterThan(0);
        }
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
