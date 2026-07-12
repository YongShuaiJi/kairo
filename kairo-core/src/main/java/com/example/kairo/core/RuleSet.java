package com.example.kairo.core;

import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokePhase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bucketed view of the compiled rules attached to one method key.
 *
 * <p>V1.3 buckets by authoritative {@link EnhancementLocation} rather than the
 * legacy {@link InvokePhase}, so the dispatcher can run exactly the locations
 * that apply to a given event (method enter / method return / method throw /
 * finally / constructor / call-site). The legacy {@link #rules(InvokePhase)}
 * and {@link #hasPhase(InvokePhase)} accessors are retained for compatibility
 * and project each phase onto its location set.
 */
public final class RuleSet {

    private static final RuleSet EMPTY = new RuleSet(List.of());

    private final List<CompiledRule> rules;
    private final Map<EnhancementLocation, List<CompiledRule>> rulesByLocation;

    public RuleSet(List<CompiledRule> rules) {
        List<CompiledRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt((CompiledRule rule) -> rule.rule().priority()).reversed()
                .thenComparing(rule -> rule.rule().id()));
        this.rules = List.copyOf(sorted);
        EnumMap<EnhancementLocation, List<CompiledRule>> byLocation = new EnumMap<>(EnhancementLocation.class);
        for (EnhancementLocation location : EnhancementLocation.values()) {
            byLocation.put(location, sorted.stream()
                    .filter(rule -> rule.rule().effectiveLocation() == location)
                    .toList());
        }
        this.rulesByLocation = Map.copyOf(byLocation);
    }

    public static RuleSet empty() {
        return EMPTY;
    }

    public List<CompiledRule> all() {
        return rules;
    }

    public List<CompiledRule> rules(EnhancementLocation location) {
        return rulesByLocation.getOrDefault(location, List.of());
    }

    public boolean hasLocation(EnhancementLocation location) {
        return !rules(location).isEmpty();
    }

    /**
     * All rules whose location belongs to one of the given locations, in
     * priority order. Used by the dispatcher to run the locations relevant to a
     * single exit event (e.g. METHOD_RETURN then METHOD_FINALLY).
     */
    public List<CompiledRule> rules(EnhancementLocation... locations) {
        if (locations.length == 0) {
            return List.of();
        }
        if (locations.length == 1) {
            return rules(locations[0]);
        }
        List<CompiledRule> out = new ArrayList<>();
        for (EnhancementLocation location : locations) {
            out.addAll(rules(location));
        }
        return out;
    }

    // -------------------------------------------------------- legacy InvokePhase compat

    public List<CompiledRule> rules(InvokePhase phase) {
        List<CompiledRule> out = new ArrayList<>();
        for (EnhancementLocation location : locationsOf(phase)) {
            out.addAll(rules(location));
        }
        return out;
    }

    public boolean hasPhase(InvokePhase phase) {
        for (EnhancementLocation location : locationsOf(phase)) {
            if (hasLocation(location)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    private static Set<EnhancementLocation> locationsOf(InvokePhase phase) {
        return switch (phase) {
            case BEFORE -> EnumSet.of(EnhancementLocation.METHOD_ENTER,
                    EnhancementLocation.CONSTRUCTOR_AFTER_SUPER, EnhancementLocation.CALL_BEFORE);
            case RETURN -> EnumSet.of(EnhancementLocation.METHOD_RETURN,
                    EnhancementLocation.METHOD_FINALLY, EnhancementLocation.CONSTRUCTOR_RETURN,
                    EnhancementLocation.CALL_RETURN);
            case THROWS -> EnumSet.of(EnhancementLocation.METHOD_THROW,
                    EnhancementLocation.CONSTRUCTOR_THROW, EnhancementLocation.CALL_THROW);
        };
    }
}
