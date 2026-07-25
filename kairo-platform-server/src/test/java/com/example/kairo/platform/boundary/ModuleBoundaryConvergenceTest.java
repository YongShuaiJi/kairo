package com.example.kairo.platform.boundary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.example.kairo.platform.boundary.BoundarySupport.hasMainClass;
import static com.example.kairo.platform.boundary.BoundarySupport.kairoDeps;
import static com.example.kairo.platform.boundary.BoundarySupport.modules;
import static com.example.kairo.platform.boundary.BoundarySupport.repoRoot;
import static com.example.kairo.platform.boundary.BoundarySupport.rootPom;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1-G convergence gate: the root Maven reactor is exactly the fixed 16 modules, the two deletion
 * targets are gone, and the documented module boundaries (attach-permission isolation, ops
 * isolation, SDK as a base library, CLI/MCP separation, Platform not pulling attach/ops) hold.
 */
class ModuleBoundaryConvergenceTest {

    private static final List<String> FIXED_REACTOR = List.of(
            "kairo-bootstrap-api",
            "kairo-api",
            "kairo-groovy",
            "kairo-core",
            "kairo-agent-core",
            "kairo-agent-server",
            "kairo-agent-core-modern",
            "kairo-agent-bootstrap",
            "kairo-attach-cli",
            "kairo-ops",
            "kairo-platform-server",
            "kairo-sdk",
            "kairo-cli",
            "kairo-mcp",
            "kairo-demo",
            "kairo-integration-tests");

    @Test
    void reactorHasExactlyTheSixteenFixedModules() throws Exception {
        assertThat(modules(rootPom()))
                .as("root reactor must be exactly the fixed 16 modules in order")
                .isEqualTo(FIXED_REACTOR);
    }

    @Test
    void deletedModulesAreGone() throws Exception {
        assertThat(Files.isDirectory(repoRoot().resolve("kairo-object")))
                .as("kairo-object module must be deleted (absorbed into kairo-core)")
                .isFalse();
        assertThat(Files.isDirectory(repoRoot().resolve("kairo-sidecar")))
                .as("kairo-sidecar module must be deleted (executor merged into kairo-attach-cli)")
                .isFalse();
        assertThat(modules(rootPom())).doesNotContain("kairo-object", "kairo-sidecar");
    }

    @Test
    void attachToolIsALeafNoOtherModuleDependsOnIt() throws Exception {
        // Attach API permission stays only in the attach tool; no platform/agent/sdk/cli/mcp/ops
        // module may depend on kairo-attach-cli.
        for (String module : FIXED_REACTOR) {
            if (module.equals("kairo-attach-cli")) {
                continue;
            }
            assertThat(kairoDeps(module))
                    .as("%s must not depend on kairo-attach-cli", module)
                    .doesNotContain("kairo-attach-cli");
        }
    }

    @Test
    void opsIsIsolatedExceptForTestOnlyIntegrationTests() throws Exception {
        // kairo-ops is a low-permission loopback tool; only the test-only integration-tests
        // module may depend on it.
        for (String module : FIXED_REACTOR) {
            if (module.equals("kairo-ops") || module.equals("kairo-integration-tests")) {
                continue;
            }
            assertThat(kairoDeps(module))
                    .as("%s must not depend on kairo-ops", module)
                    .doesNotContain("kairo-ops");
        }
    }

    @Test
    void sdkIsABaseLibrary() throws Exception {
        assertThat(kairoDeps("kairo-sdk"))
                .as("kairo-sdk is a public base library and depends only on kairo-api")
                .containsExactly("kairo-api");
    }

    @Test
    void cliAndMcpDoNotDependOnEachOther() throws Exception {
        assertThat(kairoDeps("kairo-cli")).doesNotContain("kairo-mcp");
        assertThat(kairoDeps("kairo-mcp")).doesNotContain("kairo-cli");
    }

    @Test
    void platformServerDoesNotPullAttachOrOps() throws Exception {
        assertThat(kairoDeps("kairo-platform-server"))
                .doesNotContain("kairo-attach-cli", "kairo-ops");
    }

    @Test
    void attachAndOpsDoNotDependOnEachOther() throws Exception {
        assertThat(kairoDeps("kairo-attach-cli")).doesNotContain("kairo-ops");
        assertThat(kairoDeps("kairo-ops")).doesNotContain("kairo-attach-cli");
    }

    @Test
    void agentCoreModernIsAnAssemblyWithNoMainClass() throws Exception {
        Path modern = repoRoot().resolve("kairo-agent-core-modern");
        assertThat(Files.isDirectory(modern.resolve("src/main/java")))
                .as("kairo-agent-core-modern is a shaded assembly with no main source set")
                .isFalse();
        assertThat(hasMainClass("kairo-agent-core-modern"))
                .as("kairo-agent-core-modern must declare no main class (shaded assembly)")
                .isFalse();
    }
}
