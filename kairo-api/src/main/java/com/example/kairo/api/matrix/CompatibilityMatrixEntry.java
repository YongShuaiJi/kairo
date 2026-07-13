package com.example.kairo.api.matrix;

import java.util.Objects;

/**
 * The result of evaluating one {@link CompatibilityScenario} on a runner
 * (&sect;6 / &sect;8). Carries the outcome, a reason (failure cause or skip
 * rationale) and optional evidence (test name / proof pointer).
 */
public final class CompatibilityMatrixEntry {

    private final CompatibilityScenario scenario;
    private final MatrixOutcome outcome;
    private final String reason;
    private final String evidence;

    public CompatibilityMatrixEntry(CompatibilityScenario scenario, MatrixOutcome outcome,
                                    String reason, String evidence) {
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.reason = reason;
        this.evidence = evidence;
    }

    public CompatibilityScenario scenario() {
        return scenario;
    }

    public MatrixOutcome outcome() {
        return outcome;
    }

    public String reason() {
        return reason;
    }

    public String evidence() {
        return evidence;
    }
}
