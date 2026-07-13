package com.example.kairo.api.matrix;

import com.example.kairo.api.SupportLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompatibilityMatrixModelTest {

    @Test
    void reportCountsByOutcomeAndLevel() {
        CompatibilityScenario jdk17 = new CompatibilityScenario("jdk-17", "JDK 17",
                CompatibilityCategory.JDK_VERSION, SupportLevel.SUPPORTED, "17", true, "runs on 17");
        CompatibilityScenario sb2 = new CompatibilityScenario("spring-boot-2", "Spring Boot 2.x",
                CompatibilityCategory.FRAMEWORK, SupportLevel.LIMITED, "8/11", false, "nightly");
        CompatibilityMatrixEntry pass = new CompatibilityMatrixEntry(jdk17, MatrixOutcome.PASSED, null, "Jdk17ScenarioTest");
        CompatibilityMatrixEntry doc = new CompatibilityMatrixEntry(sb2, MatrixOutcome.DOCUMENTED, "nightly matrix", null);
        CompatibilityMatrixReport report = new CompatibilityMatrixReport(List.of(pass, doc), "17", 1L, "2 entries");

        assertThat(report.entries()).hasSize(2);
        assertThat(report.count(MatrixOutcome.PASSED)).isEqualTo(1);
        assertThat(report.count(MatrixOutcome.DOCUMENTED)).isEqualTo(1);
        assertThat(report.count(SupportLevel.SUPPORTED)).isEqualTo(1);
        assertThat(report.count(SupportLevel.LIMITED)).isEqualTo(1);
        assertThat(report.runnerJdk()).isEqualTo("17");
    }
}
