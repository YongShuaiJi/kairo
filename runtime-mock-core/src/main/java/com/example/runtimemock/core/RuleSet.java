package com.example.runtimemock.core;

import com.example.runtimemock.api.InvokePhase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RuleSet {

    private static final RuleSet EMPTY = new RuleSet(List.of());

    private final List<CompiledRule> rules;
    private final Map<InvokePhase, List<CompiledRule>> rulesByPhase;

    public RuleSet(List<CompiledRule> rules) {
        List<CompiledRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt((CompiledRule rule) -> rule.rule().priority()).reversed()
                .thenComparing(rule -> rule.rule().id()));
        this.rules = List.copyOf(sorted);
        EnumMap<InvokePhase, List<CompiledRule>> byPhase = new EnumMap<>(InvokePhase.class);
        for (InvokePhase phase : InvokePhase.values()) {
            byPhase.put(phase, sorted.stream()
                    .filter(rule -> rule.rule().phase() == phase)
                    .toList());
        }
        this.rulesByPhase = Map.copyOf(byPhase);
    }

    public static RuleSet empty() {
        return EMPTY;
    }

    public List<CompiledRule> all() {
        return rules;
    }

    public List<CompiledRule> rules(InvokePhase phase) {
        return rulesByPhase.getOrDefault(phase, List.of());
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public boolean hasPhase(InvokePhase phase) {
        return !rules(phase).isEmpty();
    }
}
