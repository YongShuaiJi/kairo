package com.example.kairo.perf.soak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Short real lifecycle test for the M2-D soak harness (the PR gate, &sect;9.6 "V17-SOAK.PR").
 * Runs the harness in-process with a test-only {@link SoakClock.AcceleratedClock} (step =
 * {@code PT1M}) over a virtual duration ({@code PT321M}) so the fixed 1m/5m/30m cadence
 * fires 64 enhance/update/partial-unload/full-unload batches and 10 Agent/Platform
 * disconnect/recoveries in a few seconds of real time, while every cadence
 * boundary performs REAL lifecycle work against a real ByteBuddyAgent + AgentRuntime.
 *
 * <p>The fixed cadence and the production {@link SoakClock.WallClock} default are unchanged;
 * only the time source is replaced (M2-D brief: test-only clock/cadence injection). This is
 * the in-JVM counterpart of {@code ./scripts/v1.7/run-soak.sh --duration PT2H --output target/v1.7}
 * (RC gate, not run here).
 */
class SoakShortLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD_ID = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path outputDir;

    @Test
    void acceleratedShortRunPassesAllCadenceGates() throws Exception {
        Duration duration = Duration.ofMinutes(321);
        SoakArgumentParser.Options opts = SoakArgumentParser.parse(new String[]{
                "--duration", duration.toString(),
                "--output", outputDir.toString(),
                "--build-id", BUILD_ID,
                "--command", "./scripts/v1.7/run-soak.sh --duration PT2H --output target/v1.7",
                "--jvm-args", "-Xms512m -Xmx512m -XX:+AlwaysPreTouch",
                "--mode", "pr",
                "--working-tree-dirty", "false"
        });
        // Accelerated clock: 1 minute of virtual time per tick, so the fixed 1m/5m/30m cadence
        // fires at its natural rate over a handful of real milliseconds.
        SoakClock clock = new SoakClock.AcceleratedClock(Instant.parse("2026-07-31T00:00:00Z"),
                Duration.ofMinutes(1));

        int rc = SoakHarness.runWithClock(opts, clock);

        Path resultFile = outputDir.resolve("soak-result.json");
        if (rc != 0 && Files.exists(resultFile)) {
            JsonNode result = MAPPER.readTree(Files.readString(resultFile));
            JsonNode ff = result.path("firstFailure");
            if (ff.isObject()) {
                System.out.println("[soak] harness exited " + rc + "; firstFailure: scenario="
                        + ff.path("scenario").asText() + " phase=" + ff.path("phase").asText()
                        + " reason=" + ff.path("reason").asText() + " detail=" + ff.path("detail").asText());
            }
        }
        assertThat(rc).as("harness exit code").isZero();
        assertThat(resultFile).exists();

        JsonNode result = MAPPER.readTree(Files.readString(resultFile));
        List<String> errors = new SoakResultValidator().validate(result, duration);
        assertThat(errors).as("result schema errors:\n" + String.join("\n", errors)).isEmpty();
        assertThat(result.path("overall").asText()).isEqualTo("PASSED");
        assertThat(result.path("finalState").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("firstFailure").isNull() || result.path("firstFailure").isMissingNode())
                .as("a passing run must not report firstFailure").isTrue();

        // The fixed cadence fired a full sequence over the accelerated virtual time.
        JsonNode cycles = result.path("cycles");
        assertThat(cycles.path("continuousTargetEnhanceApplications").asInt())
                .as("the hot target must not be retransformed by lifecycle batches").isEqualTo(1);
        assertThat(cycles.path("summaries").asInt()).as("per-minute summaries").isGreaterThanOrEqualTo(30);
        assertThat(cycles.path("enhanceUnloadBatches").asInt()).as("5-minute measured batches")
                .isEqualTo((int) (duration.toMinutes() / 5));
        assertThat(cycles.path("disconnectRecoveries").asInt()).as("30-minute measured disconnect/recovery")
                .isEqualTo((int) (duration.toMinutes() / 30));
        assertThat(cycles.path("continuousInvocations").asLong()).as("continuous real invocations").isGreaterThan(0L);
        assertThat(cycles.path("failedBatches").asInt()).isZero();

        JsonNode topology = result.path("workloadTopology");
        assertThat(topology.path("classSeparated").asBoolean()).isTrue();
        assertThat(topology.path("lifecycleClassLoaderPerBatch").asBoolean()).isTrue();
        assertThat(topology.path("continuousTargetClass").asText())
                .isNotEqualTo(topology.path("lifecycleTargetClass").asText());
        assertThat(topology.path("continuousTargetParticipatesInLifecycleBatches").asBoolean()).isFalse();
        assertThat(topology.path("lifecycleTargetReceivesContinuousTraffic").asBoolean()).isFalse();

        JsonNode warmup = result.path("measurementWarmup");
        assertThat(warmup.path("enhanceUnloadBatch").asBoolean()).isTrue();
        assertThat(warmup.path("disconnectRecovery").asBoolean()).isTrue();
        assertThat(warmup.path("resourceSample").asBoolean()).isTrue();
        assertThat(warmup.path("excludedFromDurationAndCycles").asBoolean()).isTrue();
        assertThat(warmup.path("strategy").asText()).isEqualTo("bounded-adaptive-metaspace-plateau");
        assertThat(warmup.path("steadyStateEstablished").asBoolean()).isTrue();
        assertThat(warmup.path("batchesRun").asInt()).isBetween(128, 512);
        assertThat(warmup.path("observedWindowMetaspaceGrowthPct").asDouble()).isLessThanOrEqualTo(2.0);
        assertThat(warmup.path("eligibleLifecycleLoadersOutstanding").asInt())
                .isLessThanOrEqualTo(warmup.path("allowedOutstandingLifecycleLoaders").asInt());
        assertThat(warmup.path("latestCohortGraceLoaders").asInt())
                .isLessThanOrEqualTo(warmup.path("sampleEveryBatches").asInt());
        assertThat(warmup.path("samples").size()).isGreaterThanOrEqualTo(5);

        // The fixed cadence is recorded verbatim (production default unchanged).
        JsonNode cadence = result.path("cadence");
        assertThat(cadence.path("summaryInterval").asText()).isEqualTo("PT1M");
        assertThat(cadence.path("batchInterval").asText()).isEqualTo("PT5M");
        assertThat(cadence.path("disconnectInterval").asText()).isEqualTo("PT30M");

        // The disconnect/recovery really ran and recovered.
        JsonNode dr = result.path("disconnectRecovery");
        assertThat(dr.path("count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(dr.path("details")).as("cycle-zero warm-up must not leak into measured evidence")
                .hasSize((int) (duration.toMinutes() / 30));
        assertThat(dr.path("lastOutcome").asText()).isEqualTo("RECOVERED");

        // The evidence bundle remains valid after being copied out of its original output
        // directory (including a container-only /evidence mount).
        String rawPath = result.path("timeSeries").path("rawPath").asText();
        assertThat(rawPath).isEqualTo("soak-timeseries.jsonl");
        Path rawFile = outputDir.resolve("soak-timeseries.jsonl");
        assertThat(rawFile).exists();
        long lines = Files.lines(rawFile).count();
        assertThat(lines).as("raw time-series line count reconciles to count").isEqualTo(
                result.path("timeSeries").path("count").asInt());

        // The duration completed fully.
        assertThat(result.path("duration").path("completed").asBoolean()).isTrue();
        assertThat(result.path("oomEvidence").asBoolean()).isFalse();
    }

}
