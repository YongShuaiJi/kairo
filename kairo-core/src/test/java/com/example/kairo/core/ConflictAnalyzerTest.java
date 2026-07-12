package com.example.kairo.core;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ConflictKind;
import com.example.kairo.api.ConflictReport;
import com.example.kairo.api.ConflictSeverity;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictAnalyzerTest {

    private final MethodSelector selector = new MethodSelector("com.example.Svc", "loader-1", "echo", "(I)I");

    @Test
    void multipleUnconditionalTerminalRulesAreError() {
        MockRule a = rule("a", EnhancementLocation.METHOD_RETURN, 10, true);
        MockRule b = rule("b", EnhancementLocation.METHOD_RETURN, 5, true);

        ConflictReport report = new ConflictAnalyzer().analyze(java.util.List.of(a, b));

        assertThat(report.hasBlocking()).isTrue();
        assertThat(report.findings()).anyMatch(f -> f.kind() == ConflictKind.MULTIPLE_UNCONDITIONAL_TERMINATE);
    }

    @Test
    void unreachableFollowerBehindUnconditionalIsError() {
        MockRule a = rule("a", EnhancementLocation.METHOD_RETURN, 10, true); // 100% terminal
        MockRule b = rule("b", EnhancementLocation.METHOD_RETURN, 5, false);

        ConflictReport report = new ConflictAnalyzer().analyze(java.util.List.of(a, b));

        assertThat(report.findings()).anyMatch(f -> f.kind() == ConflictKind.UNREACHABLE_RULE
                && f.severity() == ConflictSeverity.ERROR);
    }

    @Test
    void unreachableBehindConditionalIsPotential() {
        MockRule a = percent(rule("a", EnhancementLocation.METHOD_RETURN, 10, true), 50);
        MockRule b = rule("b", EnhancementLocation.METHOD_RETURN, 5, false);

        ConflictReport report = new ConflictAnalyzer().analyze(java.util.List.of(a, b));

        assertThat(report.findings()).anyMatch(f -> f.kind() == ConflictKind.UNREACHABLE_RULE
                && f.severity() == ConflictSeverity.POTENTIAL);
    }

    @Test
    void mutexGroupOverlapIsError() {
        MockRule a = mutex(rule("a", EnhancementLocation.METHOD_RETURN, 10, false), "g1");
        MockRule b = mutex(rule("b", EnhancementLocation.METHOD_THROW, 5, false), "g1");

        ConflictReport report = new ConflictAnalyzer().analyze(java.util.List.of(a, b));

        assertThat(report.hasBlocking()).isTrue();
        assertThat(report.findings()).anyMatch(f -> f.kind() == ConflictKind.MUTEX_GROUP_OVERLAP);
    }

    @Test
    void capabilityTierExceedsAppLimitIsError() {
        MockRule a = profile(rule("a", EnhancementLocation.METHOD_RETURN, 10, false), CapabilityProfile.UNRESTRICTED);

        ConflictReport report = new ConflictAnalyzer(CapabilityProfile.SAFE).analyze(java.util.List.of(a));

        assertThat(report.hasBlocking()).isTrue();
        assertThat(report.findings()).anyMatch(f -> f.kind() == ConflictKind.CAPABILITY_TIER_EXCEEDS_APP_LIMIT);
    }

    @Test
    void conditionalOverlapIsPotentialNotError() {
        MockRule a = percent(rule("a", EnhancementLocation.METHOD_RETURN, 10, false), 50);
        MockRule b = percent(rule("b", EnhancementLocation.METHOD_RETURN, 5, false), 50);

        ConflictReport report = new ConflictAnalyzer().analyze(java.util.List.of(a, b));

        assertThat(report.hasBlocking()).isFalse();
        assertThat(report.hasPotential()).isTrue();
        assertThat(report.findings()).anyMatch(f -> f.kind() == ConflictKind.POTENTIAL_CONDITION_OVERLAP);
    }

    @Test
    void cleanChainHasNoFindings() {
        MockRule a = rule("a", EnhancementLocation.METHOD_ENTER, 10, false);
        MockRule b = rule("b", EnhancementLocation.METHOD_RETURN, 5, false);

        ConflictReport report = new ConflictAnalyzer().analyze(java.util.List.of(a, b));

        assertThat(report.hasFindings()).isFalse();
    }

    @Test
    void rulesAtDifferentLocationsDoNotConflict() {
        MockRule a = rule("a", EnhancementLocation.METHOD_RETURN, 10, true);
        MockRule b = rule("b", EnhancementLocation.METHOD_THROW, 10, true);

        ConflictReport report = new ConflictAnalyzer().analyze(java.util.List.of(a, b));

        // different locations (RETURN vs THROW) - no multiple-terminate conflict
        assertThat(report.findings()).noneMatch(f -> f.kind() == ConflictKind.MULTIPLE_UNCONDITIONAL_TERMINATE);
    }

    private MockRule rule(String id, EnhancementLocation location, int priority, boolean terminal) {
        return MockRule.builder()
                .id(id)
                .target(selector)
                .location(location)
                .phase(InvokePhase.BEFORE)
                .priority(priority)
                .terminal(terminal)
                .script("return null")
                .capabilityProfile(CapabilityProfile.SAFE)
                .build();
    }

    private MockRule percent(MockRule base, int pct) {
        return base.toBuilder().percentage(pct).build();
    }

    private MockRule mutex(MockRule base, String group) {
        return base.toBuilder().mutexGroup(group).build();
    }

    private MockRule profile(MockRule base, CapabilityProfile profile) {
        return base.toBuilder().capabilityProfile(profile).build();
    }
}
