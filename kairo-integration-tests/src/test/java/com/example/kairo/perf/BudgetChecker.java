package com.example.kairo.perf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure budget-evaluation logic. Given a {@link Budget} and the baseline/candidate
 * statistics for every scenario, it produces a per-scenario verdict.
 *
 * <p><b>Direction is explicit and hard-coded by the budget:</b> regression versus
 * baseline, lower-is-better, units ns-per-op. A regression is the candidate's
 * metric being <em>higher</em> than the baseline's. The checker never relaxes or
 * rewrites the budget thresholds — it only reads them.
 *
 * <p><b>Gating is explicit per scenario:</b>
 * <ul>
 *   <li><b>gated</b> scenarios (key hit/miss paths) are compared against the threshold;
 *       a regression beyond the threshold is {@link Verdict#FAIL}. A gated scenario that
 *       is {@link Verdict#NOT_COMPARABLE} (missing/non-positive samples) <b>also fails
 *       the budget</b> — it must not silently pass.</li>
 *   <li><b>observed-only</b> (non-gated) scenarios get {@link Verdict#NOT_GATED}: they are
 *       still mandatory and schema-validated (a missing/non-positive-stat scenario fails
 *       evidence via the validator), but a regression does not fail the budget.</li>
 * </ul>
 *
 * <p>This class has no I/O and no wall-clock; it is fully unit-testable with
 * fixture timings (see {@code BudgetCheckerTest}).
 */
public final class BudgetChecker {

    public enum Verdict { PASS, FAIL, NOT_COMPARABLE, NOT_GATED }

    /** A single side's measured statistics for one scenario. {@code null} stats means "not run". */
    public record ScenarioStats(double median, double p95, int sampleCount, long ops) {
        public boolean hasSamples() {
            return sampleCount > 0;
        }
    }

    public record ScenarioVerdict(
            String scenarioId,
            Verdict verdict,
            boolean gated,
            Double medianRegressionPct,
            Double p95RegressionPct,
            double thresholdMedianPct,
            double thresholdP95Pct,
            String reason) { }

    public record BudgetResult(
            boolean passed,
            List<ScenarioVerdict> verdicts,
            List<String> failedScenarios,
            List<String> nonComparableGatedScenarios,
            List<String> notGatedScenarios) {

        /** All scenarios that contributed to a budget failure (FAIL or NOT_COMPARABLE-gated). */
        public List<String> failedOrNonComparableGated() {
            java.util.List<String> out = new java.util.ArrayList<>(failedScenarios);
            out.addAll(nonComparableGatedScenarios);
            return out;
        }
    }

    /** Evaluate the budget. {@code catalogIds} is the full expected scenario set. */
    public BudgetResult check(Budget budget,
                              Map<String, ScenarioStats> baseline,
                              Map<String, ScenarioStats> candidate,
                              List<String> catalogIds) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(catalogIds, "catalogIds");

        // The budget is only meaningful for the documented direction. Refuse silently
        // re-purposing it; the reporter validates this up front, but assert here too.
        if (!"regression-vs-baseline".equals(budget.direction())
                || !"lower-is-better".equals(budget.metricDirection())) {
            throw new IllegalStateException(
                    "Unsupported budget direction/units; refusing to evaluate: "
                            + budget.direction() + "/" + budget.metricDirection());
        }

        Map<String, ScenarioVerdict> out = new LinkedHashMap<>();
        for (String id : catalogIds) {
            out.put(id, evaluateOne(budget, id,
                    baseline.get(id), candidate.get(id)));
        }
        List<String> failed = out.values().stream()
                .filter(v -> v.verdict() == Verdict.FAIL)
                .map(ScenarioVerdict::scenarioId).toList();
        // A gated scenario that is NOT_COMPARABLE fails the budget (does not silently pass).
        List<String> nonCompGated = out.values().stream()
                .filter(v -> v.verdict() == Verdict.NOT_COMPARABLE && v.gated())
                .map(ScenarioVerdict::scenarioId).toList();
        List<String> notGated = out.values().stream()
                .filter(v -> v.verdict() == Verdict.NOT_GATED)
                .map(ScenarioVerdict::scenarioId).toList();
        boolean passed = failed.isEmpty() && nonCompGated.isEmpty();
        return new BudgetResult(passed, List.copyOf(out.values()), failed, nonCompGated, notGated);
    }

    private ScenarioVerdict evaluateOne(Budget budget, String id,
                                        ScenarioStats base, ScenarioStats cand) {
        double medThr = budget.defaultMedianPct();
        double p95Thr = budget.defaultP95Pct();
        boolean gated = budget.isGated(id);

        // Observed-only scenarios: never budget-gated. Missing/invalid stats for them are
        // caught by the validator (every scenario is mandatory); here we only decide gating.
        if (!gated) {
            return new ScenarioVerdict(id, Verdict.NOT_GATED, false,
                    null, null, medThr, p95Thr, "observed-only (not budget-gated)");
        }

        if (base == null || !base.hasSamples()) {
            return new ScenarioVerdict(id, Verdict.NOT_COMPARABLE, true,
                    null, null, medThr, p95Thr, "no baseline samples (gated scenario must fail)");
        }
        if (cand == null || !cand.hasSamples()) {
            return new ScenarioVerdict(id, Verdict.NOT_COMPARABLE, true,
                    null, null, medThr, p95Thr, "no candidate samples (gated scenario must fail)");
        }
        if (base.median() <= 0.0) {
            return new ScenarioVerdict(id, Verdict.NOT_COMPARABLE, true,
                    null, null, medThr, p95Thr, "baseline median is non-positive (gated scenario must fail)");
        }
        double medianReg = regressionPct(base.median(), cand.median());
        double p95Reg = regressionPct(base.p95(), cand.p95());
        // p95 baseline could be zero while median positive; guard it.
        boolean p95Comparable = base.p95() > 0.0;
        boolean medianFail = medianReg > medThr;
        boolean p95Fail = p95Comparable && p95Reg > p95Thr;
        Verdict v = (medianFail || p95Fail) ? Verdict.FAIL : Verdict.PASS;
        String reason = v == Verdict.FAIL
                ? "medianReg=" + fmt(medianReg) + "% (thr " + medThr + ")"
                        + (p95Comparable ? ", p95Reg=" + fmt(p95Reg) + "% (thr " + p95Thr + ")" : "")
                : "within budget";
        return new ScenarioVerdict(id, v, true,
                medianReg, p95Comparable ? p95Reg : null, medThr, p95Thr, reason);
    }

    /** Regression percentage for a lower-is-better metric. Positive = slower (worse). */
    static double regressionPct(double baseline, double candidate) {
        return (candidate - baseline) / baseline * 100.0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
