package com.example.kairo.core;

import com.example.kairo.api.MockRule;
import com.example.kairo.groovy.CompiledMockScript;
import com.example.kairo.groovy.ScriptCompilerFactory;

import java.lang.reflect.Method;
import java.util.Objects;

public final class RulePublisher {

    private final ScriptCompilerFactory scriptCompilerFactory;
    private final RuleRegistry ruleRegistry;

    public RulePublisher(ScriptCompilerFactory scriptCompilerFactory, RuleRegistry ruleRegistry) {
        this.scriptCompilerFactory = Objects.requireNonNull(scriptCompilerFactory, "scriptCompilerFactory");
        this.ruleRegistry = Objects.requireNonNull(ruleRegistry, "ruleRegistry");
    }

    public CompiledRule publish(Method method, MockRule rule) {
        validateTarget(method, rule);
        CompiledMockScript script = scriptCompilerFactory.compile(method, rule);
        CompiledRule compiledRule = new CompiledRule(rule.toBuilder()
                .scriptHash(script.scriptHash())
                .build(), script);
        ruleRegistry.addRule(MethodKey.of(method), compiledRule);
        return compiledRule;
    }

    public void remove(Method method, String ruleId) {
        ruleRegistry.removeRule(MethodKey.of(method), ruleId);
    }

    private static void validateTarget(Method method, MockRule rule) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(rule, "rule");
        if (!method.getDeclaringClass().getName().equals(rule.target().className())) {
            throw new IllegalArgumentException("Rule class target does not match method");
        }
        if (!method.getName().equals(rule.target().methodName())) {
            throw new IllegalArgumentException("Rule method target does not match method");
        }
        String descriptor = MethodDescriptor.of(method);
        if (!descriptor.equals(rule.target().methodDescriptor())) {
            throw new IllegalArgumentException("Rule descriptor target does not match method: " + descriptor);
        }
        String expectedLoaderId = rule.target().classLoaderId();
        if (expectedLoaderId != null && !expectedLoaderId.equals(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))) {
            throw new IllegalArgumentException("Rule classLoader target does not match method");
        }
    }
}
