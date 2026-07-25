package com.example.kairo.core;

import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.MockRule;
import com.example.kairo.groovy.CompiledMockScript;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1 closed-loop bugfix: removing the final rule for a method/target must not leave an empty
 * {@link RuleChainSnapshot} behind in the {@link MethodChainSnapshot}. A lingering empty chain is
 * emitted by {@code RuntimeStateSnapshotBuilder} with a blank {@code chainId} and rejected by the
 * Platform REFRESH validator, breaking the M1 recovery invariant that a completed unload stays
 * observable and reconcilable.
 */
class RuleRegistryEmptyChainTest {

    private final RuleRegistry registry = new RuleRegistry();

    @Test
    void removingFinalRuleRemovesMethodEntryAndForEachChainEmitsNoEmptyChain() {
        register("only-rule", method(), EnhancementLocation.METHOD_ENTER);
        assertThat(registry.snapshot()).containsKey(methodKey());

        registry.removeRule(methodKey(), "only-rule");

        // The method entry is gone entirely (no empty bundle retained).
        assertThat(registry.snapshot()).doesNotContainKey(methodKey());
        assertThat(registry.methodChains(methodKey())).isSameAs(MethodChainSnapshot.EMPTY);

        // forEachChain must not visit any chain — in particular not an empty one with a blank chainId.
        List<RuleChainSnapshot> visited = new ArrayList<>();
        registry.forEachChain(visited::add);
        assertThat(visited).isEmpty();
    }

    @Test
    void removingFinalRuleForOneTargetPreservesAnotherNonEmptyTarget() {
        register("enter-rule", method(), EnhancementLocation.METHOD_ENTER);
        register("return-rule", method(), EnhancementLocation.METHOD_RETURN);

        registry.removeRule(methodKey(), "enter-rule");

        MethodChainSnapshot bundle = registry.methodChains(methodKey());
        // The METHOD_ENTER target was the only rule for that target: it is omitted entirely.
        assertThat(bundle.chain(EnhancementLocation.METHOD_ENTER, null))
                .as("emptied target is omitted, not retained as an empty chain")
                .isSameAs(RuleChainSnapshot.empty());
        // The METHOD_RETURN target survives unchanged with its single rule.
        RuleChainSnapshot survivor = bundle.chain(EnhancementLocation.METHOD_RETURN, null);
        assertThat(survivor.isEmpty()).isFalse();
        assertThat(survivor.rules()).hasSize(1);
        assertThat(survivor.rules().get(0).rule().id()).isEqualTo("return-rule");
        assertThat(survivor.chainId()).as("surviving chain keeps a non-blank chainId").isNotBlank();

        // forEachChain visits exactly the one surviving (non-empty) chain.
        List<RuleChainSnapshot> visited = new ArrayList<>();
        registry.forEachChain(visited::add);
        assertThat(visited).hasSize(1);
        assertThat(visited.get(0).rules()).hasSize(1);
        assertThat(visited.get(0).chainId()).isNotBlank();
    }

    // -------------------------------------------------------- helpers

    private CompiledRule register(String id, Method target, EnhancementLocation location) {
        MockRule rule = MockRule.builder()
                .id(id)
                .target(new MethodSelector(target.getDeclaringClass().getName(),
                        ClassLoaderIdentity.idOf(target.getDeclaringClass().getClassLoader()),
                        target.getName(), MethodDescriptor.of(target)))
                .location(location)
                .phase(InvokePhase.BEFORE)
                .script("return null")
                .build();
        CompiledRule compiled = new CompiledRule(rule, new NoopScript(id));
        registry.addRule(MethodKey.of(target), compiled);
        return compiled;
    }

    private static Method method() {
        try {
            return Target.class.getMethod("echo", String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodKey methodKey() {
        return MethodKey.of(method());
    }

    private static final class NoopScript implements CompiledMockScript {
        private final String id;

        NoopScript(String id) {
            this.id = id;
        }

        @Override public String ruleId() { return id; }
        @Override public long version() { return 1; }
        @Override public String scriptHash() { return "hash-" + id; }

        @Override
        public MockDecision execute(InvocationContext context) {
            return MockDecision.proceed();
        }
    }

    public static final class Target {
        public String echo(String value) {
            return value;
        }
    }
}
