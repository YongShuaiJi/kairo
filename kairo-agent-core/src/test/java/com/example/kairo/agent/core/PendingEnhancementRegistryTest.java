package com.example.kairo.agent.core;

import com.example.kairo.api.ClassSelector;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;4.4: the pending-enhancement registry pre-registers rules for unloaded
 * classes, matches them by selector when the class loads, refuses fuzzy multi-match
 * unless allMatch, and audits every materialization and ambiguity.
 */
class PendingEnhancementRegistryTest {

    private final PendingEnhancementRegistry registry = new PendingEnhancementRegistry();

    @Test
    void registerAndCancelPendingRule() {
        MockRule rule = rule("r1");
        registry.register(fuzzySelector("com.example.Foo"), rule, "alice", 1L);
        assertThat(registry.pendingCount()).isEqualTo(1);
        assertThat(registry.pending()).hasSize(1);
        assertThat(registry.pending().get(0).ruleId()).isEqualTo("r1");
        assertThat(registry.pending().get(0).actor()).isEqualTo("alice");

        assertThat(registry.cancel("r1")).isTrue();
        assertThat(registry.pendingCount()).isZero();
        assertThat(registry.cancel("r1")).isFalse();
    }

    @Test
    void exactSelectorMatchesOnlyItsLoader() {
        ClassSelector exact = ClassSelector.builder()
                .className("com.example.Foo")
                .classLoaderId("loader-A")
                .build();
        assertThat(registry.matches(exact, "com.example.Foo", "loader-A", null, null, null)).isTrue();
        assertThat(registry.matches(exact, "com.example.Foo", "loader-B", null, null, null)).isFalse();
        assertThat(registry.matches(exact, "com.example.Bar", "loader-A", null, null, null)).isFalse();
    }

    @Test
    void fuzzySelectorMatchesAllLoadersAndNarrowsByMetadata() {
        ClassSelector fuzzy = ClassSelector.builder()
                .className("com.example.Foo")
                .loaderClassName("TomcatEmbeddedWebappClassLoader")
                .moduleName("app")
                .codeSource("file:/app/foo.jar")
                .build();
        assertThat(registry.matches(fuzzy, "com.example.Foo", "loader-A",
                "TomcatEmbeddedWebappClassLoader", "app", "file:/app/foo.jar")).isTrue();
        // wrong loader class -> no match
        assertThat(registry.matches(fuzzy, "com.example.Foo", "loader-A",
                "PluginClassLoader", "app", "file:/app/foo.jar")).isFalse();
        // wrong module -> no match
        assertThat(registry.matches(fuzzy, "com.example.Foo", "loader-A",
                "TomcatEmbeddedWebappClassLoader", "other", "file:/app/foo.jar")).isFalse();
    }

    @Test
    void markResolvedAuditsMaterialization() {
        registry.register(fuzzySelector("com.example.Foo"), rule("r2"), "alice", 1L);
        ClassIdentity identity = new ClassIdentity("com.example.Foo", "loader-A");
        registry.markResolved("r2", identity, "loader-A", java.util.List.of("materialized on first load"), 2L);

        assertThat(registry.resolved()).hasSize(1);
        assertThat(registry.resolved().get(0).actualIdentity()).isEqualTo(identity);
        assertThat(registry.resolved().get(0).notes()).containsExactly("materialized on first load");
    }

    @Test
    void markAmbiguousAuditsRefusedMultiMatch() {
        registry.markAmbiguous("r3", "com.example.Foo",
                java.util.List.of("loader-A", "loader-B"), 3L);
        assertThat(registry.ambiguousMatches()).hasSize(1);
        assertThat(registry.ambiguousMatches().get(0).candidateLoaderIds())
                .containsExactly("loader-A", "loader-B");
    }

    @Test
    void allMatchSelectorIsRecordedOnEntry() {
        ClassSelector allMatch = ClassSelector.builder()
                .className("com.example.Foo")
                .allMatch(true)
                .build();
        registry.register(allMatch, rule("r4"), "bob", 1L);
        assertThat(registry.pending().get(0).selector().allMatch()).isTrue();
        assertThat(registry.pending().get(0).selector().isExact()).isFalse();
    }

    private static ClassSelector fuzzySelector(String className) {
        return ClassSelector.builder().className(className).build();
    }

    private static MockRule rule(String id) {
        Method method = nameOnly();
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .phase(InvokePhase.RETURN)
                .script("return mock.proceed()")
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private static Method nameOnly() {
        try {
            return Object.class.getDeclaredMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
