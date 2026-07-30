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

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused, deterministic reproduction of the production leak surfaced by the M2-C
 * harness (&sect;9.3). These tests do NOT run the full budget gate; they isolate the
 * exact trigger so the defect is reproducible on a tiny budget without the 10k-cycle
 * RC soak.
 *
 * <p><b>Defect summary:</b> invoking an enhanced business method that runs a Groovy
 * script rule permanently pins the business {@link ClassLoader} (and the Groovy
 * script {@code ClassLoader}). The loader is NOT reclaimable after
 * {@code runtime.remove(rule)} + {@code runtime.close()} + a bounded full-GC sequence,
 * even after clearing Groovy's {@code MetaClassRegistry} and {@code ClassInfo}. A loader
 * that is loaded/closed without an invoked rule, and a direct {@code GroovyScriptCompiler}
 * compile/close, ARE reclaimable. The leak therefore originates in the agent's
 * invoke/dispatch path (bridge + Groovy script execution), not in the Groovy compiler
 * alone and not in the publish/remove weaving path alone.
 *
 * <p>This is a <b>known production defect</b> to be fixed under
 * {@code bugfix/v1.7-groovy-invoke-classloader-leak}. The M2-C harness
 * ({@link LeakCheckHarness}) strictly enforces the documented &sect;9.3 budgets and
 * FAILS (exit 4) because of this leak; these tests characterize the trigger so the fix
 * can be verified. When the fix lands, {@code invokedGroovyRulePinsClassLoader} should
 * flip to assert the loader IS reclaimable.
 */
class LeakDefectReproTest {

    private static final String SAFE_RULE_SCRIPT = "return mock.returnValue('REPRO')";

    @Test
    void loaderWithoutInvokedRuleIsReclaimable() throws Exception {
        // Variant I: publish + remove + close with NO invocation of the enhanced method.
        // The weaving/unweaving path alone does NOT pin the loader: after close the
        // Groovy compile cache and generations are cleared, so the loader is unreachable.
        try (LeakFixtureCompiler fixtures = LeakFixtureCompiler.compile()) {
            AgentRuntime runtime = new AgentRuntime(ByteBuddyAgent.install());
            runtime.start();
            WeakReference<ClassLoader> ref;
            try {
                ref = publishRemoveNoInvoke(fixtures, runtime, "no-invoke");
            } finally {
                runtime.close();
            }
            gcLoop(15);
            assertThat(ref.get())
                    .as("loader without an invoked rule must be reclaimable after close + GC"
                            + " (contrast: the leak is triggered by invoke, not by publish/remove)")
                    .isNull();
        }
    }

    @Test
    void invokedGroovyRulePinsClassLoader() throws Exception {
        // Variant C/D: publish + INVOKE + remove + close. The invoked rule permanently
        // pins the business ClassLoader (and the Groovy script ClassLoader).
        try (LeakFixtureCompiler fixtures = LeakFixtureCompiler.compile()) {
            AgentRuntime runtime = new AgentRuntime(ByteBuddyAgent.install());
            runtime.start();
            WeakReference<ClassLoader> ref;
            try {
                ref = publishInvokeRemove(fixtures, runtime, "invoke");
            } finally {
                runtime.close();
            }
            gcLoop(10);
            // KNOWN DEFECT: the loader is NOT reclaimed. This assertion documents the
            // defect; flip to .isNull() once bugfix/v1.7-groovy-invoke-classloader-leak
            // lands and the M2-C gate goes green.
            assertThat(ref.get())
                    .as("KNOWN DEFECT: invoking a Groovy rule pins the business ClassLoader"
                            + " across remove + close + bounded GC")
                    .isNotNull();
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

    private static WeakReference<ClassLoader> publishRemoveNoInvoke(LeakFixtureCompiler fixtures,
                                                                     AgentRuntime runtime, String tag) throws Exception {
        URLClassLoader loader = newUrlLoader(fixtures);
        Class<?> clazz = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
        Method echo = clazz.getMethod("echo", String.class);
        String ruleId = "repro-" + tag;
        runtime.publish(echo, ruleFor(ruleId, clazz, echo, loader, SAFE_RULE_SCRIPT), "repro");
        // Deliberately do NOT invoke echo.
        runtime.remove(ruleId, "repro");
        WeakReference<ClassLoader> ref = new WeakReference<>(loader);
        loader.close();
        return ref;
    }

    private static WeakReference<ClassLoader> publishInvokeRemove(LeakFixtureCompiler fixtures,
                                                                   AgentRuntime runtime, String tag) throws Exception {
        URLClassLoader loader = newUrlLoader(fixtures);
        Class<?> clazz = Class.forName(fixtures.binaryName(LeakFixtureCompiler.LEAK_SERVICE), true, loader);
        Method echo = clazz.getMethod("echo", String.class);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        String ruleId = "repro-" + tag;
        runtime.publish(echo, ruleFor(ruleId, clazz, echo, loader, SAFE_RULE_SCRIPT), "repro");
        // Invoke the enhanced method: this runs the bridge + the Groovy script.
        assertThat(echo.invoke(inst, "x")).isEqualTo("REPRO");
        runtime.remove(ruleId, "repro");
        assertThat(echo.invoke(inst, "x")).isEqualTo("echo:x");
        WeakReference<ClassLoader> ref = new WeakReference<>(loader);
        loader.close();
        return ref;
    }

    private static void gcLoop(int n) throws InterruptedException {
        for (int i = 0; i < n; i++) {
            System.gc();
            Thread.sleep(40);
        }
    }
}
