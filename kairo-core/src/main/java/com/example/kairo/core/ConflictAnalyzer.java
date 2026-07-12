package com.example.kairo.core;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ConflictFinding;
import com.example.kairo.api.ConflictKind;
import com.example.kairo.api.ConflictReport;
import com.example.kairo.api.ConflictSeverity;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.MockRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Static conflict analysis for a rule chain (&sect;2.4).
 *
 * <p>The analyzer is deterministic: given the same rule list and context it
 * produces the same findings, independent of map iteration order. It groups
 * rules by their authoritative (location, call-site) target and applies the
 * V1.4 conflict catalogue:
 *
 * <ul>
 *   <li>multiple unconditional ({@link MockRule#terminal()}) terminate rules &mdash; ERROR;</li>
 *   <li>unreachable rules shadowed by an earlier unconditional rule &mdash; ERROR when the
 *       terminator always runs, POTENTIAL when it is conditional;</li>
 *   <li>mutex-group overlap &mdash; ERROR;</li>
 *   <li>exclusive call-site replacement &mdash; ERROR when both are terminal, POTENTIAL otherwise;</li>
 *   <li>capability tier exceeding the application limit &mdash; ERROR;</li>
 *   <li>target revision / call-site fingerprint drift &mdash; ERROR (when a drift context is supplied);</li>
 *   <li>overlapping conditional rules whose business overlap cannot be decided statically &mdash; POTENTIAL.</li>
 * </ul>
 *
 * <p>The analyzer never pretends to have proved a POTENTIAL conflict. Business
 * overlap that cannot be statically decided is surfaced as
 * {@link ConflictSeverity#POTENTIAL} for explicit user confirmation.
 */
public final class ConflictAnalyzer {

    /**
     * Optional drift context. When supplied, the analyzer also checks call-site
     * fingerprint drift against the live JVM state. Target transformation
     * revision drift is a chain-level concern (spec vs loaded-class) and is
     * checked in the Agent apply path, where both values are known.
     */
    public record DriftContext(Function<CallSiteSelector, String> liveFingerprint) {
    }

    private final CapabilityProfile appMaxCapability;
    private final DriftContext driftContext;

    public ConflictAnalyzer() {
        this(null, null);
    }

    public ConflictAnalyzer(CapabilityProfile appMaxCapability) {
        this(appMaxCapability, null);
    }

    public ConflictAnalyzer(CapabilityProfile appMaxCapability, DriftContext driftContext) {
        this.appMaxCapability = appMaxCapability;
        this.driftContext = driftContext;
    }

    public ConflictReport analyze(List<MockRule> rules) {
        Objects.requireNonNull(rules, "rules");
        List<ConflictFinding> findings = new ArrayList<>();

        // Global: capability tier vs app limit.
        if (appMaxCapability != null) {
            for (MockRule rule : rules) {
                CapabilityProfile profile = rule.capabilityProfile();
                if (profile != null && profile.ordinal() > appMaxCapability.ordinal()) {
                    findings.add(ConflictFinding.of(ConflictKind.CAPABILITY_TIER_EXCEEDS_APP_LIMIT,
                            ConflictSeverity.ERROR,
                            "Rule " + rule.id() + " capability " + profile
                                    + " exceeds application limit " + appMaxCapability,
                            rule.id()));
                }
            }
        }

        // Global: mutex-group overlap (rules sharing a mutex group must not both be enabled).
        List<MockRule> mutexRules = rules.stream()
                .filter(r -> r.mutexGroup() != null)
                .toList();
        for (int i = 0; i < mutexRules.size(); i++) {
            for (int j = i + 1; j < mutexRules.size(); j++) {
                MockRule a = mutexRules.get(i);
                MockRule b = mutexRules.get(j);
                if (a.mutexGroup().equals(b.mutexGroup())) {
                    findings.add(ConflictFinding.of(ConflictKind.MUTEX_GROUP_OVERLAP,
                            ConflictSeverity.ERROR,
                            "Rules " + a.id() + " and " + b.id()
                                    + " share mutex group '" + a.mutexGroup() + "'",
                            a.id(), b.id()));
                }
            }
        }

        // Drift: call-site fingerprint (target transformation revision is checked at apply).
        if (driftContext != null && driftContext.liveFingerprint() != null) {
            for (MockRule rule : rules) {
                CallSiteSelector selector = rule.callSiteSelector();
                if (selector != null && selector.fingerprint() != null) {
                    String live = driftContext.liveFingerprint().apply(selector);
                    if (live == null) {
                        findings.add(ConflictFinding.of(ConflictKind.CALL_SITE_FINGERPRINT_DRIFT,
                                ConflictSeverity.ERROR,
                                "Call site for rule " + rule.id() + " is no longer present",
                                rule.id()));
                    } else if (!live.equals(selector.fingerprint())) {
                        findings.add(ConflictFinding.of(ConflictKind.CALL_SITE_FINGERPRINT_DRIFT,
                                ConflictSeverity.ERROR,
                                "Call site fingerprint for rule " + rule.id() + " has drifted",
                                rule.id()));
                    }
                }
            }
        }

        // Per-target grouping: terminal / unreachable / exclusive / potential overlap.
        List<List<MockRule>> groups = groupByTarget(rules);
        for (List<MockRule> group : groups) {
            analyzeGroup(group, findings);
        }

        return new ConflictReport(findings);
    }

    private void analyzeGroup(List<MockRule> group, List<ConflictFinding> findings) {
        if (group.size() < 2) {
            return;
        }
        // group is already in canonical order (priority desc, createdAt asc, id asc) because
        // the input rules list is canonicalized by the caller; re-sort defensively.
        List<MockRule> ordered = new ArrayList<>(group);
        ordered.sort((a, b) -> {
            int c = Integer.compare(b.priority(), a.priority());
            if (c != 0) return c;
            c = Long.compare(a.createdAt(), b.createdAt());
            if (c != 0) return c;
            return a.id().compareTo(b.id());
        });

        boolean callSite = ordered.get(0).callSiteSelector() != null;
        boolean seenUnconditionalTerminal = false;
        for (int i = 0; i < ordered.size(); i++) {
            MockRule current = ordered.get(i);
            if (current.terminal()) {
                if (seenUnconditionalTerminal) {
                    findings.add(ConflictFinding.of(ConflictKind.MULTIPLE_UNCONDITIONAL_TERMINATE,
                            ConflictSeverity.ERROR,
                            "Rule " + current.id() + " is an unconditional terminate rule but "
                                    + ordered.get(i - 1).id() + " already terminates this phase",
                            current.id(), ordered.get(i - 1).id()));
                }
                seenUnconditionalTerminal = true;
                // Followers are unreachable (deterministic when the terminator always runs).
                for (int j = i + 1; j < ordered.size(); j++) {
                    MockRule follower = ordered.get(j);
                    ConflictSeverity sev = current.percentage() >= 100
                            ? ConflictSeverity.ERROR : ConflictSeverity.POTENTIAL;
                    findings.add(ConflictFinding.of(ConflictKind.UNREACHABLE_RULE,
                            sev,
                            "Rule " + follower.id() + " is unreachable behind unconditional rule "
                                    + current.id(),
                            follower.id(), current.id()));
                }
            } else if (mayReplaceOutcome(current)) {
                // Two non-terminal outcome-replacing rules at the same target: business overlap
                // cannot be decided statically.
                for (int j = 0; j < i; j++) {
                    MockRule earlier = ordered.get(j);
                    if (earlier.terminal()) {
                        continue; // already reported as unreachable
                    }
                    if (mayReplaceOutcome(earlier) && overlapsConditionally(earlier, current)) {
                        findings.add(ConflictFinding.of(ConflictKind.POTENTIAL_CONDITION_OVERLAP,
                                ConflictSeverity.POTENTIAL,
                                "Rules " + earlier.id() + " and " + current.id()
                                        + " may both replace the outcome; business overlap cannot be decided statically",
                                earlier.id(), current.id()));
                    }
                }
            }
            if (callSite && mayReplaceOutcome(current)) {
                for (int j = 0; j < i; j++) {
                    MockRule earlier = ordered.get(j);
                    if (mayReplaceOutcome(earlier) && overlapsConditionally(earlier, current)) {
                        ConflictSeverity sev = (earlier.terminal() && current.terminal())
                                ? ConflictSeverity.ERROR : ConflictSeverity.POTENTIAL;
                        findings.add(ConflictFinding.of(ConflictKind.EXCLUSIVE_CALL_SITE_REPLACEMENT,
                                sev,
                                "Rules " + earlier.id() + " and " + current.id()
                                        + " both exclusively replace call-site " + current.callSiteSelector(),
                                earlier.id(), current.id()));
                    }
                }
            }
        }
    }

    private boolean mayReplaceOutcome(MockRule rule) {
        EnhancementLocation location = rule.effectiveLocation();
        return location.isReturnLocation() || location.isThrowLocation() || rule.terminal();
    }

    private boolean overlapsConditionally(MockRule a, MockRule b) {
        // Two rules overlap when both can fire on the same invocation. percentage==100 for both
        // means unconditional overlap; otherwise the sampler may or may not co-fire, which is a
        // POTENTIAL overlap we cannot statically resolve.
        return a.percentage() > 0 && b.percentage() > 0;
    }

    private List<List<MockRule>> groupByTarget(List<MockRule> rules) {
        java.util.LinkedHashMap<String, List<MockRule>> groups = new java.util.LinkedHashMap<>();
        for (MockRule rule : rules) {
            String key = targetKey(rule);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        }
        return new ArrayList<>(groups.values());
    }

    private static String targetKey(MockRule rule) {
        CallSiteSelector selector = rule.callSiteSelector();
        if (selector != null) {
            return rule.effectiveLocation() + "|" + selector.owner() + "." + selector.name()
                    + selector.descriptor() + "#" + selector.occurrenceIndex();
        }
        return rule.effectiveLocation() + "|" + rule.target().className() + "."
                + rule.target().methodName() + rule.target().methodDescriptor();
    }
}
