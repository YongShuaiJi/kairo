package com.example.kairo.compatmatrix;

import java.util.List;
import java.util.Objects;

/**
 * One frozen row of the V1.7 real-process compatibility matrix (&sect;10.1).
 *
 * <p>This is the M3-A contract catalog entry - <strong>not</strong> row evidence.
 * It records the exact declared expectation (runner OS/arch, target JDK(s), load
 * mode, fixture, required behavior, support level and owning M3 work package) so
 * that row evidence produced by {@code run-compatibility.sh} and consumed by the
 * aggregator can be checked against a single frozen source of truth. The catalog
 * is immutable; nothing in M3-A mutates it.
 *
 * <p>Fields are transcribed verbatim from &sect;10.1 so a scenario can never be
 * silently re-pointed at a different OS, JDK, load mode or fixture.
 */
public final class CompatibilityScenario {

    private final String id;
    private final CompatibilitySupportLevel supportLevel;
    private final String runnerOs;        // normalized: "Linux" / "macOS"
    private final String runnerArch;      // normalized: "x86_64" / "arm64"
    private final List<Integer> targetJdks;
    private final LoadMode loadMode;
    private final String loadModeRaw;     // verbatim §10.1 "加载" cell
    private final String fixture;         // verbatim §10.1 fixture cell
    private final String requiredBehaviorsRaw;  // verbatim §10.1 "必须行为" cell
    private final List<String> requiredBehaviors;  // tokenized on "、"
    private final String workPackage;     // M3-B / M3-C / M3-D / M3-E
    private final String description;

    public CompatibilityScenario(String id, CompatibilitySupportLevel supportLevel,
                                 String runnerOs, String runnerArch, List<Integer> targetJdks,
                                 LoadMode loadMode, String loadModeRaw, String fixture,
                                 String requiredBehaviorsRaw, List<String> requiredBehaviors,
                                 String workPackage, String description) {
        this.id = requireText(id, "id");
        this.supportLevel = Objects.requireNonNull(supportLevel, "supportLevel");
        this.runnerOs = requireText(runnerOs, "runnerOs");
        this.runnerArch = requireText(runnerArch, "runnerArch");
        this.targetJdks = List.copyOf(Objects.requireNonNull(targetJdks, "targetJdks"));
        if (this.targetJdks.isEmpty()) {
            throw new IllegalArgumentException("targetJdks must not be empty for " + id);
        }
        this.loadMode = Objects.requireNonNull(loadMode, "loadMode");
        this.loadModeRaw = requireText(loadModeRaw, "loadModeRaw");
        this.fixture = requireText(fixture, "fixture");
        this.requiredBehaviorsRaw = requireText(requiredBehaviorsRaw, "requiredBehaviorsRaw");
        this.requiredBehaviors = List.copyOf(Objects.requireNonNull(requiredBehaviors, "requiredBehaviors"));
        if (this.requiredBehaviors.isEmpty()) {
            throw new IllegalArgumentException("requiredBehaviors must not be empty for " + id);
        }
        this.workPackage = requireText(workPackage, "workPackage");
        this.description = requireText(description, "description");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String id() {
        return id;
    }

    public CompatibilitySupportLevel supportLevel() {
        return supportLevel;
    }

    public String runnerOs() {
        return runnerOs;
    }

    public String runnerArch() {
        return runnerArch;
    }

    public List<Integer> targetJdks() {
        return targetJdks;
    }

    public LoadMode loadMode() {
        return loadMode;
    }

    public String loadModeRaw() {
        return loadModeRaw;
    }

    public String fixture() {
        return fixture;
    }

    public String requiredBehaviorsRaw() {
        return requiredBehaviorsRaw;
    }

    public List<String> requiredBehaviors() {
        return requiredBehaviors;
    }

    public String workPackage() {
        return workPackage;
    }

    public String description() {
        return description;
    }

    /** Whether the scenario is committed (must reach PASSED for the M3 gate). */
    public boolean isFormal() {
        return supportLevel.isFormal();
    }
}
