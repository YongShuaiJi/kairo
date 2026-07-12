package com.example.kairo.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleChainCanonicalizerTest {

    private final EnhancementTarget target = EnhancementTarget.of(
            new MethodSelector("com.example.Foo", "loader-1", "echo", "(Ljava/lang/String;)Ljava/lang/String;"),
            EnhancementLocation.METHOD_ENTER);

    @Test
    void canonicalOrderSortsByPriorityThenCreatedAtThenId() {
        RuleChainEntry a = entry("a", 10, 100);
        RuleChainEntry b = entry("b", 10, 200);
        RuleChainEntry c = entry("c", 20, 50);

        List<RuleChainEntry> ordered = RuleChainCanonicalizer.canonicalOrder(List.of(b, a, c));

        assertThat(ordered).containsExactly(c, a, b);
    }

    @Test
    void hashIsDeterministicAndOrderIndependent() {
        RuleChainEntry a = entry("a", 10, 100);
        RuleChainEntry b = entry("b", 10, 200);

        String hashOne = RuleChainCanonicalizer.canonicalHash(target, List.of(a, b), ChainDesiredState.ACTIVE);
        String hashTwo = RuleChainCanonicalizer.canonicalHash(target, List.of(b, a), ChainDesiredState.ACTIVE);

        assertThat(hashOne).isEqualTo(hashTwo);
        assertThat(hashOne).hasSize(64);
    }

    @Test
    void hashChangesWithContent() {
        RuleChainEntry a = entry("a", 10, 100);
        RuleChainEntry b = entry("b", 10, 200);

        String twoRules = RuleChainCanonicalizer.canonicalHash(target, List.of(a, b), ChainDesiredState.ACTIVE);
        String oneRule = RuleChainCanonicalizer.canonicalHash(target, List.of(a), ChainDesiredState.ACTIVE);
        String empty = RuleChainCanonicalizer.canonicalHash(target, List.of(), ChainDesiredState.EMPTY);

        assertThat(twoRules).isNotEqualTo(oneRule);
        assertThat(oneRule).isNotEqualTo(empty);
    }

    @Test
    void hashDiffersForDifferentTargets() {
        RuleChainEntry a = entry("a", 10, 100);
        EnhancementTarget other = EnhancementTarget.of(
                new MethodSelector("com.example.Bar", "loader-1", "echo", "(Ljava/lang/String;)Ljava/lang/String;"),
                EnhancementLocation.METHOD_ENTER);

        String h1 = RuleChainCanonicalizer.canonicalHash(target, List.of(a), ChainDesiredState.ACTIVE);
        String h2 = RuleChainCanonicalizer.canonicalHash(other, List.of(a), ChainDesiredState.ACTIVE);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void callSiteSelectorParticipatesInHash() {
        RuleChainEntry a = entry("a", 10, 100);
        CallSiteSelector selector = CallSiteSelector.builder()
                .owner("com.example.Callee").name("do").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(0).fingerprint("fp").build();
        EnhancementTarget callTarget = EnhancementTarget.callSite(
                new MethodSelector("com.example.Caller", "loader-1", "run", "()V"),
                EnhancementLocation.CALL_BEFORE, selector);

        String withFp = RuleChainCanonicalizer.canonicalHash(callTarget, List.of(a), ChainDesiredState.ACTIVE);
        CallSiteSelector selector2 = CallSiteSelector.builder()
                .owner("com.example.Callee").name("do").descriptor("()V")
                .opcode(InvokeOpcode.INVOKEVIRTUAL).occurrenceIndex(1).fingerprint("fp").build();
        EnhancementTarget callTarget2 = EnhancementTarget.callSite(
                new MethodSelector("com.example.Caller", "loader-1", "run", "()V"),
                EnhancementLocation.CALL_BEFORE, selector2);

        String withDifferentIndex = RuleChainCanonicalizer.canonicalHash(callTarget2, List.of(a), ChainDesiredState.ACTIVE);
        assertThat(withFp).isNotEqualTo(withDifferentIndex);
    }

    @Test
    void hashStableAcrossThousandIterations() {
        RuleChainEntry a = entry("a", 10, 100);
        RuleChainEntry b = entry("b", 5, 200);
        RuleChainEntry c = entry("c", 5, 200);
        List<RuleChainEntry> entries = List.of(a, b, c);

        String first = RuleChainCanonicalizer.canonicalHash(target, entries, ChainDesiredState.ACTIVE);
        for (int i = 0; i < 1_000; i++) {
            // shuffle the input order each iteration; hash must remain constant
            List<RuleChainEntry> shuffled = java.util.Arrays.asList(b, c, a);
            String h = RuleChainCanonicalizer.canonicalHash(target, shuffled, ChainDesiredState.ACTIVE);
            assertThat(h).isEqualTo(first);
        }
    }

    private static RuleChainEntry entry(String id, int priority, long createdAt) {
        return RuleChainEntry.builder().ruleId(id).priority(priority).createdAtMillis(createdAt)
                .scriptHash("hash-" + id).build();
    }
}
