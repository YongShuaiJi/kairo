package com.example.kairo.platform.boundary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static com.example.kairo.platform.boundary.BoundarySupport.hasMainClass;
import static com.example.kairo.platform.boundary.BoundarySupport.modules;
import static com.example.kairo.platform.boundary.BoundarySupport.repoRoot;
import static com.example.kairo.platform.boundary.BoundarySupport.rootPom;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1-G product entrypoint inventory gate: only Platform Server and Platform Web are counted as
 * central applications; the Runtime Agent is an embedded runtime; SDK/CLI/MCP/Attach/Ops are
 * independent tools; Demo is a fixture and integration-tests is test-only. Asserts the
 * classification partitions the fixed reactor and verifies expected entrypoints.
 */
class ProductEntrypointInventoryTest {

    private static final String PLATFORM_SERVER = "kairo-platform-server";
    private static final String PLATFORM_WEB = "kairo-platform-web";

    private static final Set<String> INTERNAL_LIBS = setOf(
            "kairo-bootstrap-api", "kairo-api", "kairo-groovy", "kairo-core");
    private static final Set<String> EMBEDDED_RUNTIME = setOf(
            "kairo-agent-bootstrap", "kairo-agent-core", "kairo-agent-server", "kairo-agent-core-modern");
    private static final Set<String> TOOLS = setOf(
            "kairo-sdk", "kairo-cli", "kairo-mcp", "kairo-attach-cli", "kairo-ops");
    private static final Set<String> FIXTURE = setOf("kairo-demo");
    private static final Set<String> TEST_ONLY = setOf("kairo-integration-tests");

    @Test
    void classificationPartitionsTheFixedReactor() throws Exception {
        Set<String> classified = new TreeSet<>();
        classified.addAll(INTERNAL_LIBS);
        classified.addAll(EMBEDDED_RUNTIME);
        classified.addAll(TOOLS);
        classified.addAll(FIXTURE);
        classified.addAll(TEST_ONLY);
        classified.add(PLATFORM_SERVER);
        assertThat(classified)
                .as("classification must partition exactly the fixed reactor modules")
                .isEqualTo(new TreeSet<>(modules(rootPom())));
    }

    @Test
    void exactlyTwoCentralApplications() throws Exception {
        // Platform Server is the single Maven central application.
        assertThat(modules(rootPom())).contains(PLATFORM_SERVER);
        // Platform Web is the second central application but is an independent Node workspace,
        // not a Maven module, so it is deliberately absent from the Maven reactor count.
        assertThat(modules(rootPom())).doesNotContain(PLATFORM_WEB);
        Path web = repoRoot().resolve(PLATFORM_WEB);
        assertThat(Files.isRegularFile(web.resolve("package.json")))
                .as("Platform Web must be a Node workspace (package.json)")
                .isTrue();
        assertThat(Files.isRegularFile(web.resolve("Dockerfile")))
                .as("Platform Web must have its own Dockerfile (independent image)")
                .isTrue();
    }

    @Test
    void runtimeAgentIsEmbeddedNotACentralApplication() {
        // The Agent is an embedded runtime living in the target JVM, not a central platform app.
        assertThat(EMBEDDED_RUNTIME).doesNotContain(PLATFORM_SERVER, PLATFORM_WEB);
        assertThat(TOOLS).doesNotContain(PLATFORM_SERVER, PLATFORM_WEB);
    }

    @Test
    void centralApplicationsHaveExpectedEntryPoints() throws Exception {
        assertThat(hasMainClass(PLATFORM_SERVER))
                .as("Platform Server must declare a main class (KairoPlatformApplication)")
                .isTrue();
        Path web = repoRoot().resolve(PLATFORM_WEB);
        assertThat(Files.readString(web.resolve("package.json")).contains("\"scripts\""))
                .as("Platform Web must declare npm scripts (build entrypoint)")
                .isTrue();
    }

    @Test
    void independentToolsHaveExpectedEntryPoints() throws Exception {
        assertThat(hasMainClass("kairo-attach-cli"))
                .as("Attach CLI must declare a main class (AttachCommand; exec subcommand runs the executor)")
                .isTrue();
        assertThat(hasMainClass("kairo-ops")).as("Ops CLI must declare a main class").isTrue();
        assertThat(hasMainClass("kairo-cli")).as("Platform CLI must declare a main class").isTrue();
        assertThat(hasMainClass("kairo-mcp")).as("MCP server must declare a main class").isTrue();
        // SDK is a public Java library, not a runnable entrypoint.
        assertThat(hasMainClass("kairo-sdk"))
                .as("SDK is a library and must not declare a main class")
                .isFalse();
    }

    @Test
    void assemblyAndTestOnlyHaveNoMainClass() throws Exception {
        assertThat(hasMainClass("kairo-agent-core-modern"))
                .as("agent-core-modern is a shaded assembly and may have zero main classes")
                .isFalse();
        assertThat(hasMainClass("kairo-integration-tests"))
                .as("integration-tests is test-only and must not declare a main class")
                .isFalse();
    }

    @Test
    void demoIsAFixtureNotACentralApplication() {
        // kairo-demo is a runnable acceptance target but is a fixture, not a central application.
        assertThat(FIXTURE).containsExactly("kairo-demo");
        assertThat(INTERNAL_LIBS).doesNotContain("kairo-demo");
    }

    private static Set<String> setOf(String... values) {
        return new TreeSet<>(Set.of(values));
    }
}
