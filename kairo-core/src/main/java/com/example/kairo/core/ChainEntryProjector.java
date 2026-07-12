package com.example.kairo.core;

import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.RuleChainEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge between the runtime {@link CompiledRule} list and the portable
 * {@link RuleChainEntry} model used for canonical ordering and hashing.
 *
 * <p>The Agent holds compiled rules (which carry live stats and scripts); the
 * Platform and the hash only care about the rule identity, version, priority,
 * creation time, script hash and mutex group. This projector strips the runtime
 * state so the same {@link com.example.kairo.api.RuleChainCanonicalizer} computes
 * the hash on both sides.
 */
public final class ChainEntryProjector {

    private ChainEntryProjector() {
    }

    /** Project a compiled-rule list to canonical rule-chain entries. */
    public static List<RuleChainEntry> project(List<CompiledRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<RuleChainEntry> entries = new ArrayList<>(rules.size());
        for (CompiledRule compiled : rules) {
            entries.add(project(compiled));
        }
        return entries;
    }

    public static RuleChainEntry project(CompiledRule compiled) {
        return RuleChainEntry.builder()
                .ruleId(compiled.rule().id())
                .version(compiled.rule().version())
                .priority(compiled.rule().priority())
                .createdAtMillis(compiled.rule().createdAt())
                .scriptHash(compiled.rule().scriptHash() == null ? "" : compiled.rule().scriptHash())
                .mutexGroup(compiled.rule().mutexGroup())
                .build();
    }

    /**
     * The desired state for a snapshot projected from a compiled-rule list:
     * {@link ChainDesiredState#EMPTY} when the list is empty, otherwise
     * {@link ChainDesiredState#ACTIVE}.
     */
    public static ChainDesiredState desiredStateFor(List<CompiledRule> rules) {
        return rules == null || rules.isEmpty() ? ChainDesiredState.EMPTY : ChainDesiredState.ACTIVE;
    }
}
