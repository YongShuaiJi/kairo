package com.example.kairo.agent.core.matrix;

import com.example.kairo.api.SupportLevel;
import com.example.kairo.api.matrix.CompatibilityCategory;
import com.example.kairo.api.matrix.CompatibilityMatrixEntry;
import com.example.kairo.api.matrix.CompatibilityMatrixReport;
import com.example.kairo.api.matrix.MatrixOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;6: the compatibility matrix runs on the live JDK with no agent and produces
 * an honest report. In-process scenarios (JDK proxy, bridge, synthetic, lambda, the
 * running JDK's own version) must PASS; scenarios needing another JDK or a live agent are
 * SKIPPED; LIMITED/EXPERIMENTAL documentation scenarios are DOCUMENTED. Nothing FAILED,
 * nothing silently omitted.
 */
class CompatibilityMatrixTest {

    @Test
    void reportCoversEveryDeclaredScenarioWithNoFailures() {
        CompatibilityMatrixReport report = new CompatibilityMatrixRunner().run(CompatibilityMatrixRunner.Context.empty());

        assertThat(report.entries()).hasSize(29);
        assertThat(report.count(MatrixOutcome.FAILED))
                .as("no scenario may FAIL on the runner").isZero();

        // Every §6 category is represented.
        for (CompatibilityCategory category : CompatibilityCategory.values()) {
            assertThat(report.entries()).anyMatch(e -> e.scenario().category() == category);
        }
    }

    @Test
    void runningJdkScenarioPassesOthersSkip() {
        String runner = Integer.toString(Runtime.version().feature());
        CompatibilityMatrixReport report = new CompatibilityMatrixRunner(runner).run(CompatibilityMatrixRunner.Context.empty());

        CompatibilityMatrixEntry own = entry(report, "jdk-" + runner);
        assertThat(own.outcome()).isEqualTo(MatrixOutcome.PASSED);

        // A different JDK version scenario is SKIPPED, not failed or silently passed.
        String other = runner.equals("17") ? "jdk-8" : "jdk-17";
        CompatibilityMatrixEntry skipped = entry(report, other);
        assertThat(skipped.outcome()).isEqualTo(MatrixOutcome.SKIPPED);
        assertThat(skipped.reason()).contains("requires JDK");
    }

    @Test
    void inProcessProxyAndPolicyScenariosPass() {
        CompatibilityMatrixReport report = new CompatibilityMatrixRunner().run(CompatibilityMatrixRunner.Context.empty());

        assertThat(entry(report, "proxy-jdk").outcome()).isEqualTo(MatrixOutcome.PASSED);
        assertThat(entry(report, "method-bridge").outcome()).isEqualTo(MatrixOutcome.PASSED);
        assertThat(entry(report, "method-synthetic").outcome()).isEqualTo(MatrixOutcome.PASSED);
        assertThat(entry(report, "method-lambda").outcome()).isEqualTo(MatrixOutcome.PASSED);
    }

    @Test
    void agentDependentScenariosSkipWithoutAgent() {
        CompatibilityMatrixReport report = new CompatibilityMatrixRunner().run(CompatibilityMatrixRunner.Context.empty());

        CompatibilityMatrixEntry firstLoad = entry(report, "life-first-load");
        assertThat(firstLoad.scenario().supportLevel()).isEqualTo(SupportLevel.SUPPORTED);
        assertThat(firstLoad.outcome()).isEqualTo(MatrixOutcome.SKIPPED);
        assertThat(firstLoad.reason()).contains("live agent");
    }

    @Test
    void documentedScenariosCarryEvidenceOrReason() {
        CompatibilityMatrixReport report = new CompatibilityMatrixRunner().run(CompatibilityMatrixRunner.Context.empty());

        for (CompatibilityMatrixEntry e : report.entries()) {
            if (e.outcome() == MatrixOutcome.DOCUMENTED) {
                assertThat(e.reason()).isNotNull();
            }
        }
        assertThat(report.summary()).contains("passed").contains("documented");
    }

    private static CompatibilityMatrixEntry entry(CompatibilityMatrixReport report, String id) {
        return report.entries().stream()
                .filter(e -> e.scenario().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing scenario " + id));
    }
}
