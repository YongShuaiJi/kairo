package com.example.kairo.agent.core.matrix;

import com.example.kairo.api.matrix.CompatibilityMatrixEntry;
import com.example.kairo.api.matrix.CompatibilityMatrixReport;
import com.example.kairo.api.matrix.MatrixOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;6 / &sect;8: the formal matrix harness executes every in-process scenario against a
 * live agent installed via {@link net.bytebuddy.agent.ByteBuddyAgent}. The agent-dependent
 * scenarios (first-load, retransform, real redefine drift, hot-update, custom/parent-child
 * loaders, generated proxies, load modes) must PASS - not SKIPPED or DOCUMENTED - on the locally
 * available JDK. Other declared JDKs are exercised via the multi-JDK harness / CI workflow.
 */
class CompatibilityMatrixHarnessTest {

    @Test
    void currentJdkAgentDependentScenariosPassWithLiveAgent() {
        CompatibilityMatrixReport report = CompatibilityMatrixHarness.runOnCurrentJdk(true);

        assertThat(report.count(MatrixOutcome.FAILED))
                .as("no scenario may FAIL on the live-agent runner").isZero();

        assertThat(outcome(report, "proxy-cglib")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "proxy-bytebuddy")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "proxy-jdk")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "spring-boot-2")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "spring-boot-3")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "tomcat-webapp")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "cl-custom")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "cl-parent-child-samename")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "life-first-load")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "life-retransform")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "life-redefine")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "life-hot-update")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "load-premain")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "load-agentmain")).isEqualTo(MatrixOutcome.PASSED);
        assertThat(outcome(report, "load-attach-cli")).isEqualTo(MatrixOutcome.PASSED);
    }

    @Test
    void externalFixtureScenariosAreSkippedWithCiPointerNotDocumented() {
        CompatibilityMatrixReport report = CompatibilityMatrixHarness.runOnCurrentJdk(true);

        // Spring AOP and Kotlin need external fixtures not on the agent-core test classpath:
        // SKIPPED with a CI pointer, never a silent DOCUMENTED placeholder.
        CompatibilityMatrixEntry springAop = entry(report, "proxy-spring-aop");
        assertThat(springAop.outcome()).isEqualTo(MatrixOutcome.SKIPPED);
        assertThat(springAop.reason()).contains("CI");

        CompatibilityMatrixEntry kotlin = entry(report, "lang-kotlin-method");
        assertThat(kotlin.outcome()).isEqualTo(MatrixOutcome.SKIPPED);
        assertThat(kotlin.reason()).contains("CI");
    }

    @Test
    void multiJdkReportCoversEveryDeclaredJdkWithNoFailuresOnCurrent() {
        CompatibilityMatrixHarness.MultiJdkMatrixReport multi = CompatibilityMatrixHarness.runMultiJdk();

        assertThat(multi.byJdk().keySet()).containsExactlyInAnyOrderElementsOf(
                CompatibilityMatrixHarness.DECLARED_JDKS);
        CompatibilityMatrixReport current = multi.byJdk().get(multi.currentJdk());
        assertThat(current).as("current JDK report").isNotNull();
        assertThat(current.count(MatrixOutcome.FAILED)).isZero();
        // JDKs not available locally are SKIPPED (not absent, not FAILED).
        for (String jdk : CompatibilityMatrixHarness.DECLARED_JDKS) {
            if (jdk.equals(multi.currentJdk())) {
                continue;
            }
            CompatibilityMatrixReport other = multi.byJdk().get(jdk);
            assertThat(other).as("JDK " + jdk + " report").isNotNull();
            assertThat(other.count(MatrixOutcome.FAILED)).isZero();
        }
    }

    private static MatrixOutcome outcome(CompatibilityMatrixReport report, String id) {
        return entry(report, id).outcome();
    }

    private static CompatibilityMatrixEntry entry(CompatibilityMatrixReport report, String id) {
        return report.entries().stream()
                .filter(e -> e.scenario().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing scenario " + id));
    }
}
