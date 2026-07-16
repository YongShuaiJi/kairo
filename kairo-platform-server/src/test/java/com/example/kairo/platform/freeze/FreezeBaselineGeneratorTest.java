package com.example.kairo.platform.freeze;

import com.example.kairo.api.protocol.AgentCommandEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit, disabled-by-default generator for the V1.6 contract floor used by V1.7.
 *
 * <p>This generator deliberately does <em>not</em> inspect the current Git HEAD.  It requires a
 * separate, clean checkout of the exact V1.6.0 commit plus the raw OpenAPI document captured from
 * a process built and started in that checkout.  That closes the previous loophole where a dirty
 * V1.7 worktree happened to point at the V1.6 commit and silently generated V1.7 data.
 *
 * <p>Reproduction:
 * <ol>
 *   <li>{@code git worktree add --detach /tmp/kairo-v16-baseline V1.6.0}</li>
 *   <li>build/start its platform with an isolated H2 database and save {@code /v3/api-docs}</li>
 *   <li>run this test with {@code kairo.freeze.v16.sourceRoot} and
 *       {@code kairo.freeze.v16.openapi} set.</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "kairo.freeze.generate", matches = "true")
class FreezeBaselineGeneratorTest {

    static final String HISTORICAL_COMMAND_WIRE_RESOURCE =
            "v1.7/fixtures/v1.6-agent-command-wire.json";
    static final String HISTORICAL_DB_MILESTONES_RESOURCE =
            "v1.7/fixtures/historical-db-milestones.json";
    static final String V17_AGENT_TO_V16_PLATFORM_RESOURCE =
            "v1.7/fixtures/v1.7-agent-to-v1.6-platform-registration.json";

    static final String V16_BASELINE_COMMIT =
            "113823b41981a2d8fb5473a772ae2d2938d9582e";
    static final String V16_TREE = "a1a858d0a377b6162f8283fa1d357e55c9da83d4";
    static final String V16_OPENAPI_SHA256 =
            "aefb4f9b7d1eb5855e3c6218e503bdaf419de1e0edc41083e49946ef39684400";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> BASELINES = List.of(
            "v1.7/migration-v1-hashes.json",
            "v1.7/config-v1-frozen.json",
            "v1.7/agent-protocol-v1.json",
            "v1.7/error-codes-v1.json",
            "v1.7/api-v1-openapi-normalized.json");

    @Test
    void generateAllBaselinesFromIsolatedV16Checkout() throws Exception {
        Path sourceRoot = requiredPath("kairo.freeze.v16.sourceRoot");
        Path rawOpenApi = requiredPath("kairo.freeze.v16.openapi");
        verifyHistoricalCheckout(sourceRoot);

        byte[] openApiBytes = Files.readAllBytes(rawOpenApi);
        assertThat(sha256(openApiBytes))
                .as("raw OpenAPI must be the independently captured V1.6.0 document")
                .isEqualTo(V16_OPENAPI_SHA256);
        JsonNode openApi = JSON.readTree(openApiBytes);
        assertThat(openApi.path("info").path("version").asText()).isEqualTo("1.6");

        // The explicit catalog owns semantics; source scans are secondary completeness guards.
        ConfigCatalogCoverageTest.assertEnvironmentCatalogMatches(sourceRoot);
        ConfigCatalogCoverageTest.assertSpringCatalogMatches(sourceRoot);

        HistoricalProtocol protocol = loadHistoricalProtocol(sourceRoot);
        verifyHistoricalCommandWireFixture();

        FreezeBaselineSupport.writeBaseline(BASELINES.get(0),
                FreezeCollectors.collectMigrations(sourceRoot));
        FreezeBaselineSupport.writeBaseline(BASELINES.get(1), FreezeCollectors.collectConfig());
        FreezeBaselineSupport.writeBaseline(BASELINES.get(2), FreezeCollectors.collectProtocol(
                protocol.versions(), protocol.capabilities()));
        FreezeModels.ErrorCatalog historicalErrors = HistoricalErrorCatalog.fromSourceRoot(sourceRoot);
        HistoricalErrorCatalog.assertRepresentedByCurrentCatalog(historicalErrors);
        FreezeBaselineSupport.writeBaseline(BASELINES.get(3), historicalErrors);
        FreezeBaselineSupport.writeBaseline(BASELINES.get(4), FreezeCollectors.normalizeOpenApi(
                FreezeCollectors.enrichAuthorization(openApi)));
        writeProvenance(sourceRoot, rawOpenApi);
    }

    private static void verifyHistoricalCheckout(Path root) throws Exception {
        assertThat(root).isDirectory();
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(V16_BASELINE_COMMIT);
        assertThat(git(root, "rev-parse", "HEAD^{tree}")).isEqualTo(V16_TREE);
        assertThat(git(root, "status", "--porcelain", "--untracked-files=no"))
                .as("historical checkout must contain no tracked edits").isEmpty();
        assertThat(git(repositoryRoot(), "cat-file", "-t", "V1.6.0"))
                .as("V1.6.0 must be an annotated tag, not a movable branch/lightweight tag")
                .isEqualTo("tag");
        assertThat(git(repositoryRoot(), "rev-parse", "V1.6.0^{commit}"))
                .isEqualTo(V16_BASELINE_COMMIT);
    }

    private static HistoricalProtocol loadHistoricalProtocol(Path root) throws Exception {
        Path classes = root.resolve("kairo-agent-server/target/classes");
        assertThat(classes.resolve(
                "com/example/kairo/agent/server/protocol/AgentProtocolInfo.class"))
                .as("build the clean V1.6 checkout before generating baselines").isRegularFile();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
            Class<?> type = Class.forName(
                    "com.example.kairo.agent.server.protocol.AgentProtocolInfo", true, loader);
            Object info = type.getMethod("defaultV1").invoke(null);
            @SuppressWarnings("unchecked")
            List<String> versions = (List<String>) type.getMethod("protocolVersions").invoke(info);
            @SuppressWarnings("unchecked")
            Set<String> capabilities = (Set<String>) type.getMethod("capabilities").invoke(info);
            return new HistoricalProtocol(List.copyOf(versions), Set.copyOf(capabilities));
        }
    }

    private static void verifyHistoricalCommandWireFixture() throws Exception {
        JsonNode fixture;
        try (InputStream in = FreezeBaselineGeneratorTest.class.getClassLoader()
                .getResourceAsStream(HISTORICAL_COMMAND_WIRE_RESOURCE)) {
            assertThat(in).as("historical V1.6 command capture").isNotNull();
            fixture = JSON.readTree(in);
        }
        Set<String> fixtureFields = new LinkedHashSet<>();
        fixture.fieldNames().forEachRemaining(fixtureFields::add);
        Set<String> dtoFields = new LinkedHashSet<>();
        for (RecordComponent component : AgentCommandEnvelope.class.getRecordComponents()) {
            com.fasterxml.jackson.annotation.JsonProperty property = component.getAccessor()
                    .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            dtoFields.add(property == null ? component.getName() : property.value());
        }
        assertThat(fixtureFields).hasSize(24)
                .containsExactlyInAnyOrderElementsOf(dtoFields);
    }

    private static void writeProvenance(Path sourceRoot, Path rawOpenApi) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String baseline : BASELINES) {
            hashes.put(baseline, sha256(Files.readAllBytes(
                    FreezeBaselineSupport.baselinePath(baseline))));
        }
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("sourceTag", "V1.6.0");
        provenance.put("sourceCommit", V16_BASELINE_COMMIT);
        provenance.put("sourceTree", V16_TREE);
        provenance.put("sourceCheckoutMode", "separate detached clean worktree");
        provenance.put("sourceCheckoutClean", true);
        provenance.put("rawOpenApiSha256", sha256(Files.readAllBytes(rawOpenApi)));
        provenance.put("historicalCommandWireResource", HISTORICAL_COMMAND_WIRE_RESOURCE);
        provenance.put("historicalCommandWireSha256",
                resourceSha256(HISTORICAL_COMMAND_WIRE_RESOURCE));
        provenance.put("historicalDbMilestonesResource", HISTORICAL_DB_MILESTONES_RESOURCE);
        provenance.put("historicalDbMilestonesSha256",
                resourceSha256(HISTORICAL_DB_MILESTONES_RESOURCE));
        provenance.put("v17AgentToV16PlatformResource", V17_AGENT_TO_V16_PLATFORM_RESOURCE);
        provenance.put("v17AgentToV16PlatformSha256",
                resourceSha256(V17_AGENT_TO_V16_PLATFORM_RESOURCE));
        provenance.put("baselineSha256", hashes);
        provenance.put("classification", "historical V1.6 compatibility floor");
        FreezeBaselineSupport.writeBaseline("v1.7/v1.6-baseline-provenance.json", provenance);
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required -D" + property + "=<path>");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int exit = process.waitFor();
        String text = output.toString(StandardCharsets.UTF_8).trim();
        if (exit != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + text);
        }
        return text;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String resourceSha256(String resource) throws Exception {
        try (InputStream in = FreezeBaselineGeneratorTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("historical fixture missing: " + resource);
            }
            return sha256(in.readAllBytes());
        }
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

    private record HistoricalProtocol(List<String> versions, Set<String> capabilities) {
    }
}
