package com.example.kairo.compatmatrix;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static assertions over the authoritative {@code .github/workflows/compatibility-matrix.yml}
 * (M3-F, section 10.4.6). The workflow is parsed as text (no YAML dependency is on the
 * test classpath) and asserted to: cover all C01-C10 rows, run formal rows on Linux
 * x86_64 and C09 on macOS arm64, wire the fixed scripts and the doc/manifest cross-check,
 * keep {@code fail-fast: false} and {@code if: always()} aggregation, and contain no
 * {@code continue-on-error} or obsolete V1.5 in-process/probe/kotlin jobs.
 */
class CompatibilityWorkflowStaticTest {

    private static final String WORKFLOW = readWorkflow();

    private static String readWorkflow() {
        try {
            return Files.readString(CompatibilityRepoPaths.workflow());
        } catch (Exception e) {
            throw new AssertionError("could not read workflow: " + e.getMessage(), e);
        }
    }

    @Test
    void noContinueOnErrorKeyAnywhere() {
        // Formal rows must not set the continue-on-error key; the matrix is fail-closed.
        // Matches the YAML key form (optionally under a list item, indented), not the word
        // appearing in a documentation comment.
        Matcher m = Pattern.compile("(?m)^\\s*-?\\s*continue-on-error\\s*:").matcher(WORKFLOW);
        assertThat(m.find())
                .as("workflow must not set continue-on-error on any job/step").isFalse();
    }

    @Test
    void allTenScenariosPresentOnCorrectRunners() {
        // Parse the matrix include: each "- scenario: C0X" followed by "runner: <runner>".
        Pattern p = Pattern.compile("- scenario: (C\\w+)\\s+runner: (\\S+)");
        Matcher m = p.matcher(WORKFLOW);
        Map<String, String> runnerByScenario = new LinkedHashMap<>();
        while (m.find()) {
            runnerByScenario.put(m.group(1), m.group(2));
        }
        assertThat(runnerByScenario.keySet())
                .as("workflow covers all C01-C10").containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.of(
                                "C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09", "C10")
                                .toList());
        // Formal rows (C01-C08, C10) run on Linux x86_64; C09 on macOS arm64.
        for (String id : new String[]{"C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C10"}) {
            assertThat(runnerByScenario.get(id))
                    .as(id + " must run on pinned Ubuntu 24.04 (Linux x86_64)")
                    .isEqualTo("ubuntu-24.04");
        }
        assertThat(runnerByScenario.get("C09"))
                .as("C09 must run on the GitHub-hosted macOS arm64 label").isEqualTo("macos-14");
    }

    @Test
    void fixedScriptsWired() {
        assertThat(WORKFLOW).contains("scripts/v1.7/run-compatibility.sh");
        assertThat(WORKFLOW).contains("scripts/v1.7/aggregate-compatibility.sh");
        assertThat(WORKFLOW).contains("scripts/v1.7/generate-compatibility-doc.sh");
        assertThat(WORKFLOW).contains("scripts/v1.7/verify-compatibility.sh");
    }

    @Test
    void verifyStepCrossChecksDocumentAndManifest() {
        // The verify step must bind the aggregate, document and manifest together.
        assertThat(WORKFLOW).contains("--doc");
        assertThat(WORKFLOW).contains("--manifest");
        assertThat(WORKFLOW).contains("v1.7-acceptance-manifest.json");
    }

    @Test
    void aggregateJobDownloadsAllRowsAndUploadsResultAndDocument() {
        assertThat(WORKFLOW).contains("needs: row");
        assertThat(WORKFLOW).contains("if: always()");
        assertThat(WORKFLOW).contains("pattern: compat-row-*");
        assertThat(WORKFLOW).contains("merge-multiple: true");
        assertThat(WORKFLOW).contains("name: compatibility-result");
        assertThat(WORKFLOW).contains("target/v1.7/compatibility-result.json");
        assertThat(WORKFLOW).contains("docs/compatibility/v1.7.md");
    }

    @Test
    void failFastFalseSoAllRowsRun() {
        assertThat(WORKFLOW).contains("fail-fast: false");
    }

    @Test
    void everyRowUploadsEvidenceArtifact() {
        // Each row job uploads its row JSON as an artifact (no silent drops).
        assertThat(WORKFLOW).containsPattern("actions/upload-artifact@[0-9a-f]{40}");
        assertThat(WORKFLOW).contains("name: compat-row-${{ matrix.scenario }}");
        assertThat(WORKFLOW).contains("if-no-files-found: error");
    }

    @Test
    void thirdPartyActionsArePinnedToImmutableCommits() {
        Matcher actions = Pattern.compile("uses:\\s+([^\\s]+/[^@\\s]+)@([^\\s#]+)").matcher(WORKFLOW);
        int count = 0;
        while (actions.find()) {
            count++;
            assertThat(actions.group(2))
                    .as(actions.group(1) + " must use an immutable 40-hex revision")
                    .matches("[0-9a-f]{40}");
        }
        assertThat(count).as("workflow must contain pinned third-party actions").isGreaterThan(0);
    }

    @Test
    void obsoleteV15JobsRemoved() {
        // The obsolete V1.5 in-process / probe / kotlin jobs must be gone.
        assertThat(WORKFLOW).doesNotContain("CompatibilityMatrixHarness");
        assertThat(WORKFLOW).doesNotContain("legacy-jdk-probe");
        assertThat(WORKFLOW).doesNotContain("kotlin-matrix");
        assertThat(WORKFLOW).doesNotContain("spring-boot-matrix");
        assertThat(WORKFLOW).doesNotContain("full-matrix");
        assertThat(WORKFLOW).doesNotContain("fast-matrix");
    }

    @Test
    void jdk17TargetJdkProvidedForJdk17Scenarios() {
        // C01/C02/C07 target JDK 17; the workflow installs JDK 17 and exports KAIRO_JDK17_HOME.
        assertThat(WORKFLOW).contains("needs_jdk17: true");
        assertThat(WORKFLOW).contains("if: matrix.needs_jdk17");
        assertThat(WORKFLOW).contains("KAIRO_JDK17_HOME");
        assertThat(WORKFLOW).contains("KAIRO_JDK21_HOME");
    }
}
