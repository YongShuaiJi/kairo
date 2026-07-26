package com.example.kairo.perf;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetCheckerTest {

    private static final List<String> IDS = ScenarioCatalog.ids();
    private static final List<String> GATED = ScenarioCatalog.gatedIds();
    // All scenarios are scenarioIds; gatedIds is the gated subset.
    private static final Budget BUDGET =
            new Budget("1.0", "regression-vs-baseline", "ns-per-op", "lower-is-better",
                    20, 20, IDS, GATED);

    private static BudgetChecker.ScenarioStats st(double median, double p95, int n) {
        return new BudgetChecker.ScenarioStats(median, p95, n, 20_000);
    }

    private BudgetChecker.BudgetResult check(String id,
                                             BudgetChecker.ScenarioStats base,
                                             BudgetChecker.ScenarioStats cand) {
        java.util.Map<String, BudgetChecker.ScenarioStats> b = new java.util.HashMap<>();
        java.util.Map<String, BudgetChecker.ScenarioStats> c = new java.util.HashMap<>();
        b.put(id, base);
        c.put(id, cand);
        return new BudgetChecker().check(BUDGET, b, c, List.of(id));
    }

    @Test
    void withinBudgetGatedPasses() {
        var r = check("before-hit", st(100, 120, 10), st(110, 130, 10));
        assertThat(r.passed()).isTrue();
        assertThat(r.failedScenarios()).isEmpty();
        var v = r.verdicts().get(0);
        assertThat(v.verdict()).isEqualTo(BudgetChecker.Verdict.PASS);
        assertThat(v.gated()).isTrue();
        assertThat(v.medianRegressionPct()).isCloseTo(10.0, within(1e-9));
        assertThat(v.p95RegressionPct()).isCloseTo(8.333333, within(1e-5));
    }

    @Test
    void medianRegressionOverThresholdFails() {
        var r = check("before-hit", st(100, 100, 10), st(125, 100, 10));
        assertThat(r.passed()).isFalse();
        assertThat(r.failedScenarios()).containsExactly("before-hit");
        var v = r.verdicts().get(0);
        assertThat(v.verdict()).isEqualTo(BudgetChecker.Verdict.FAIL);
        assertThat(v.gated()).isTrue();
        assertThat(v.medianRegressionPct()).isCloseTo(25.0, within(1e-9));
    }

    @Test
    void p95RegressionOverThresholdFails() {
        var r = check("before-hit", st(100, 100, 10), st(100, 130, 10));
        assertThat(r.passed()).isFalse();
        var v = r.verdicts().get(0);
        assertThat(v.verdict()).isEqualTo(BudgetChecker.Verdict.FAIL);
        assertThat(v.medianRegressionPct()).isCloseTo(0.0, within(1e-9));
        assertThat(v.p95RegressionPct()).isCloseTo(30.0, within(1e-9));
    }

    @Test
    void exactlyAtThresholdPasses() {
        // 20% regression == threshold; the rule is strictly-greater, so it passes.
        var r = check("before-hit", st(100, 100, 10), st(120, 120, 10));
        assertThat(r.passed()).isTrue();
        var v = r.verdicts().get(0);
        assertThat(v.verdict()).isEqualTo(BudgetChecker.Verdict.PASS);
        assertThat(v.medianRegressionPct()).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void candidateFasterIsNegativeRegressionAndPasses() {
        var r = check("before-hit", st(100, 120, 10), st(80, 90, 10));
        assertThat(r.passed()).isTrue();
        var v = r.verdicts().get(0);
        assertThat(v.medianRegressionPct()).isCloseTo(-20.0, within(1e-9));
    }

    // -------------------------------------------------- gated NOT_COMPARABLE fails

    @Test
    void gatedMissingBaselineFailsBudget() {
        var r = check("before-hit", null, st(100, 120, 10));
        assertThat(r.passed()).isFalse();
        assertThat(r.nonComparableGatedScenarios()).containsExactly("before-hit");
        assertThat(r.failedScenarios()).isEmpty();
        var v = r.verdicts().get(0);
        assertThat(v.verdict()).isEqualTo(BudgetChecker.Verdict.NOT_COMPARABLE);
        assertThat(v.gated()).isTrue();
    }

    @Test
    void gatedMissingCandidateFailsBudget() {
        var r = check("before-hit", st(100, 120, 10), null);
        assertThat(r.passed()).isFalse();
        assertThat(r.nonComparableGatedScenarios()).containsExactly("before-hit");
        assertThat(r.verdicts().get(0).verdict()).isEqualTo(BudgetChecker.Verdict.NOT_COMPARABLE);
    }

    @Test
    void gatedZeroSampleBaselineFailsBudget() {
        var r = check("before-hit", st(100, 120, 0), st(100, 120, 10));
        assertThat(r.passed()).isFalse();
        assertThat(r.nonComparableGatedScenarios()).containsExactly("before-hit");
    }

    @Test
    void gatedNonPositiveBaselineMedianFailsBudget() {
        var r = check("before-hit", st(0, 0, 10), st(100, 120, 10));
        assertThat(r.passed()).isFalse();
        assertThat(r.nonComparableGatedScenarios()).containsExactly("before-hit");
    }

    // -------------------------------------------------- observed-only (NOT_GATED)

    @Test
    void observedOnlyRegressionDoesNotFailBudget() {
        // no-agent-baseline is observed-only; even a 1000% regression must not fail.
        var r = check("no-agent-baseline", st(2, 3, 10), st(22, 33, 10));
        assertThat(r.passed()).isTrue();
        assertThat(r.failedScenarios()).isEmpty();
        assertThat(r.nonComparableGatedScenarios()).isEmpty();
        var v = r.verdicts().get(0);
        assertThat(v.verdict()).isEqualTo(BudgetChecker.Verdict.NOT_GATED);
        assertThat(v.gated()).isFalse();
        assertThat(r.notGatedScenarios()).containsExactly("no-agent-baseline");
    }

    @Test
    void observedOnlyMissingSamplesDoesNotFailBudgetButFailsEvidence() {
        // The checker does not fail the budget for a non-gated missing scenario;
        // the validator (ResultValidator) separately enforces that every scenario
        // is mandatory. This test pins the checker's narrow responsibility.
        var r = check("event-buffer-full-drop", null, st(100, 120, 10));
        assertThat(r.passed()).isTrue();
        assertThat(r.notGatedScenarios()).containsExactly("event-buffer-full-drop");
        // Even a non-gated missing-baseline side is NOT_GATED, not NOT_COMPARABLE.
        assertThat(r.verdicts().get(0).verdict()).isEqualTo(BudgetChecker.Verdict.NOT_GATED);
    }

    // -------------------------------------------------- mixed / structural

    @Test
    void mixedPassAndFailReportsOnlyFailures() {
        Map<String, BudgetChecker.ScenarioStats> base = Map.of(
                "before-hit", st(100, 100, 10),
                "return-hit", st(100, 100, 10));
        Map<String, BudgetChecker.ScenarioStats> cand = Map.of(
                "before-hit", st(110, 110, 10),     // pass
                "return-hit", st(200, 200, 10));    // fail (100%)
        var r = new BudgetChecker().check(BUDGET, base, cand, List.of("before-hit", "return-hit"));
        assertThat(r.passed()).isFalse();
        assertThat(r.failedScenarios()).containsExactly("return-hit");
    }

    @Test
    void gatedFailPlusObservedOnlyNotGatedPassesObservedButFailsOverall() {
        Map<String, BudgetChecker.ScenarioStats> base = Map.of(
                "before-hit", st(100, 100, 10),
                "no-agent-baseline", st(2, 3, 10));
        Map<String, BudgetChecker.ScenarioStats> cand = Map.of(
                "before-hit", st(200, 200, 10),       // gated FAIL
                "no-agent-baseline", st(22, 33, 10)); // observed-only, NOT_GATED
        var r = new BudgetChecker().check(BUDGET, base, cand,
                List.of("before-hit", "no-agent-baseline"));
        assertThat(r.passed()).isFalse();
        assertThat(r.failedScenarios()).containsExactly("before-hit");
        assertThat(r.notGatedScenarios()).containsExactly("no-agent-baseline");
    }

    @Test
    void unsupportedDirectionIsRejected() {
        Budget bad = new Budget("1.0", "absolute-slo", "ns-per-op", "lower-is-better", 20, 20, IDS, GATED);
        assertThatThrownBy(() -> new BudgetChecker().check(bad,
                Map.of("before-hit", st(100, 100, 10)),
                Map.of("before-hit", st(100, 100, 10)),
                List.of("before-hit")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void budgetThresholdsAreReadVerbatimNotRelaxed() {
        Budget tight = new Budget("1.0", "regression-vs-baseline", "ns-per-op", "lower-is-better",
                5, 5, IDS, GATED);
        var r = new BudgetChecker().check(tight,
                Map.of("before-hit", st(100, 100, 10)),
                Map.of("before-hit", st(110, 110, 10)), // 10% > 5%
                List.of("before-hit"));
        assertThat(r.passed()).isFalse();
        var v = r.verdicts().get(0);
        assertThat(v.thresholdMedianPct()).isEqualTo(5.0);
        assertThat(v.thresholdP95Pct()).isEqualTo(5.0);
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
