package com.example.kairo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class RuleRegistry {

    private final ConcurrentHashMap<MethodKey, AtomicReference<RuleSet>> rules = new ConcurrentHashMap<>();

    public RuleSet rules(MethodKey methodKey) {
        AtomicReference<RuleSet> reference = rules.get(methodKey);
        return reference == null ? RuleSet.empty() : reference.get();
    }

    public RuleSet addRule(MethodKey methodKey, CompiledRule compiledRule) {
        AtomicReference<RuleSet> reference = rules.computeIfAbsent(methodKey,
                ignored -> new AtomicReference<>(RuleSet.empty()));
        while (true) {
            RuleSet current = reference.get();
            List<CompiledRule> nextRules = new ArrayList<>(current.all().stream()
                    .filter(existing -> !existing.rule().id().equals(compiledRule.rule().id()))
                    .toList());
            nextRules.add(compiledRule);
            RuleSet next = new RuleSet(nextRules);
            if (reference.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    public void replace(MethodKey methodKey, RuleSet ruleSet) {
        if (ruleSet == null || ruleSet.isEmpty()) {
            rules.remove(methodKey);
            return;
        }
        rules.compute(methodKey, (ignored, existing) -> {
            if (existing == null) {
                return new AtomicReference<>(ruleSet);
            }
            existing.set(ruleSet);
            return existing;
        });
    }

    public RuleSet restoreRule(MethodKey methodKey, String ruleId, CompiledRule previousRule) {
        AtomicReference<RuleSet> reference = rules.computeIfAbsent(methodKey,
                ignored -> new AtomicReference<>(RuleSet.empty()));
        while (true) {
            RuleSet current = reference.get();
            List<CompiledRule> nextRules = new ArrayList<>(current.all().stream()
                    .filter(existing -> !existing.rule().id().equals(ruleId))
                    .toList());
            if (previousRule != null) {
                nextRules.add(previousRule);
            }
            RuleSet next = new RuleSet(nextRules);
            if (reference.compareAndSet(current, next)) {
                if (next.isEmpty()) {
                    rules.remove(methodKey, reference);
                }
                return next;
            }
        }
    }

    public RuleSet removeRule(MethodKey methodKey, String ruleId) {
        AtomicReference<RuleSet> reference = rules.get(methodKey);
        if (reference == null) {
            return RuleSet.empty();
        }
        while (true) {
            RuleSet current = reference.get();
            RuleSet next = new RuleSet(current.all().stream()
                    .filter(rule -> !rule.rule().id().equals(ruleId))
                    .toList());
            if (reference.compareAndSet(current, next)) {
                if (next.isEmpty()) {
                    rules.remove(methodKey, reference);
                }
                return next;
            }
        }
    }

    public void clear() {
        rules.clear();
    }

    public java.util.Map<MethodKey, RuleSet> snapshot() {
        java.util.Map<MethodKey, RuleSet> snapshot = new java.util.LinkedHashMap<>();
        rules.forEach((methodKey, reference) -> snapshot.put(methodKey, reference.get()));
        return java.util.Collections.unmodifiableMap(snapshot);
    }
}
