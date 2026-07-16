package com.example.kairo.platform.freeze;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that committed V1.6 contract floors still match their immutable provenance manifest. */
class BaselineProvenanceTest {

    @Test
    void baselinesAreByteIdenticalToTheIsolatedV16GenerationEvidence() throws Exception {
        JsonNode provenance;
        try (InputStream in = resource("v1.7/v1.6-baseline-provenance.json")) {
            provenance = FreezeBaselineSupport.mapper().readTree(in);
        }
        assertThat(provenance.path("sourceTag").asText()).isEqualTo("V1.6.0");
        assertThat(provenance.path("sourceCommit").asText())
                .isEqualTo(FreezeBaselineGeneratorTest.V16_BASELINE_COMMIT);
        assertThat(provenance.path("sourceTree").asText())
                .isEqualTo(FreezeBaselineGeneratorTest.V16_TREE);
        assertThat(provenance.path("sourceCheckoutMode").asText())
                .isEqualTo("separate detached clean worktree");
        assertThat(provenance.path("sourceCheckoutClean").asBoolean()).isTrue();
        assertThat(provenance.path("rawOpenApiSha256").asText())
                .isEqualTo(FreezeBaselineGeneratorTest.V16_OPENAPI_SHA256);

        assertThat(git("cat-file", "-t", "V1.6.0")).isEqualTo("tag");
        assertThat(git("rev-parse", "V1.6.0^{commit}"))
                .isEqualTo(FreezeBaselineGeneratorTest.V16_BASELINE_COMMIT);
        assertThat(git("rev-parse", "V1.6.0^{tree}"))
                .isEqualTo(FreezeBaselineGeneratorTest.V16_TREE);

        JsonNode hashes = provenance.path("baselineSha256");
        assertThat(hashes.size()).isEqualTo(5);
        hashes.fields().forEachRemaining(entry -> {
            try {
                assertThat(sha256(Files.readAllBytes(
                        FreezeBaselineSupport.baselinePath(entry.getKey()))))
                        .as("frozen baseline digest: " + entry.getKey())
                        .isEqualTo(entry.getValue().asText());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        String fixture = provenance.path("historicalCommandWireResource").asText();
        assertThat(fixture).isEqualTo(
                FreezeBaselineGeneratorTest.HISTORICAL_COMMAND_WIRE_RESOURCE);
        try (InputStream in = resource(fixture)) {
            assertThat(sha256(in.readAllBytes()))
                    .isEqualTo(provenance.path("historicalCommandWireSha256").asText());
        }
        String milestones = provenance.path("historicalDbMilestonesResource").asText();
        assertThat(milestones).isEqualTo(
                FreezeBaselineGeneratorTest.HISTORICAL_DB_MILESTONES_RESOURCE);
        try (InputStream in = resource(milestones)) {
            assertThat(sha256(in.readAllBytes()))
                    .isEqualTo(provenance.path("historicalDbMilestonesSha256").asText());
        }
        String crossVersion = provenance.path("v17AgentToV16PlatformResource").asText();
        assertThat(crossVersion).isEqualTo(
                FreezeBaselineGeneratorTest.V17_AGENT_TO_V16_PLATFORM_RESOURCE);
        try (InputStream in = resource(crossVersion)) {
            assertThat(sha256(in.readAllBytes()))
                    .isEqualTo(provenance.path("v17AgentToV16PlatformSha256").asText());
        }

        FreezeModels.ErrorCatalog historical = HistoricalErrorCatalog.fromSources(
                historicalPlatformJavaSources());
        FreezeModels.ErrorCatalog frozen = FreezeBaselineSupport.readBaseline(
                "v1.7/error-codes-v1.json", FreezeModels.ErrorCatalog.class);
        assertThat(frozen.codes())
                .as("frozen error catalog must be derived from V1.6 source, not current V1.7 code")
                .containsExactlyElementsOf(historical.codes());
        HistoricalErrorCatalog.assertRepresentedByCurrentCatalog(historical);
    }

    private static InputStream resource(String name) {
        InputStream in = BaselineProvenanceTest.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("Missing resource: " + name);
        }
        return in;
    }

    private static List<String> historicalPlatformJavaSources() throws Exception {
        String prefix = "kairo-platform-server/src/main/java";
        List<String> paths = git("ls-tree", "-r", "--name-only",
                FreezeBaselineGeneratorTest.V16_BASELINE_COMMIT, "--", prefix).lines()
                .filter(path -> path.endsWith(".java")).toList();
        List<String> sources = new ArrayList<>(paths.size());
        for (String path : paths) {
            sources.add(git("show", FreezeBaselineGeneratorTest.V16_BASELINE_COMMIT + ":" + path));
        }
        return sources;
    }

    private static String git(String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(repositoryRoot().toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int exit = process.waitFor();
        assertThat(exit).as(String.join(" ", command)).isZero();
        return output.toString(StandardCharsets.UTF_8).trim();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("kairo-platform-server"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root");
        }
        return current;
    }
}
