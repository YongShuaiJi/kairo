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
 * <p><b>Defect summary:</b> before the fix, invoking an enhanced business method that
 * ran a Groovy rule could pin both the business {@link ClassLoader} and the Groovy script
 * loader after {@code runtime.remove(rule)}. Heap-root analysis found three independent
 * lifecycle roots: the JDK JavaBeans introspection cache, Groovy indy method handles
 * adapted to generated script types, and lazily-created dispatcher workers inheriting
 * the submitting business thread's security context/ClassLoader. The fix flushes
 * generated script types from JavaBeans caches, uses Groovy's unload-safe classic call
 * sites, and creates workers with Kairo's stable security context and context loader.
 *
 * <p>This regression test covers the production defect fixed under
 * {@code bugfix/v1.7-groovy-invoke-classloader-leak}. The M2-C harness
 * ({@link LeakCheckHarness}) strictly enforces the documented &sect;9.3 budgets.
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
    void invokedGroovyRuleReleasesClassLoaderWhileRuntimeRemainsOpen() throws Exception {
        // Variant C/D: publish + INVOKE + remove while the Agent remains open. The
        // generated script class must not keep the business ClassLoader alive after
        // the rule leaves the immutable registry snapshot.
        try (LeakFixtureCompiler fixtures = LeakFixtureCompiler.compile()) {
            AgentRuntime runtime = new AgentRuntime(ByteBuddyAgent.install());
            runtime.start();
            try {
                WeakReference<ClassLoader> ref =
                        publishInvokeRemove(fixtures, runtime, "invoke");
                gcLoop(10);
                assertThat(ref.get())
                        .as("invoking then removing a Groovy rule must not pin the business"
                                + " ClassLoader while the Agent remains open")
                        .isNull();
            } finally {
                runtime.close();
            }
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
