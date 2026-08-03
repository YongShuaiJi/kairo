package com.example.kairo.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M5-A &sect;12.1: structural reactor version-consistency contract. Asserts the whole Maven
 * reactor shares one CI-friendly version family: the root project version is {@code ${revision}} with
 * the {@code 1.7.0-SNAPSHOT} developer default, every child module's parent version is {@code ${revision}}
 * (no module hard-codes {@code 0.1.0-SNAPSHOT}), internal {@code com.example.kairo} dependencies keep
 * using {@code ${project.version}}, and the shaded executable manifests carry
 * {@code Implementation-Version} so the packaged build identity is configured.
 *
 * <p>The runtime resolution (all modules evaluating to one value) is verified separately by
 * {@code mvn help:evaluate -Dexpression=project.version}; this test guards the POM source structure that
 * makes that resolution true and stays green across CI runs.
 */
class ReactorVersionConsistencyTest {

    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");
    // Matches an internal <dependency> on com.example.kairo and captures its <version>. The <parent>
    // block uses <parent>, not <dependency>, so it is excluded. Order (groupId, artifactId, version) is
    // fixed across the reactor POMs.
    private static final Pattern INTERNAL_DEP_PATTERN = Pattern.compile(
            "<dependency>\\s*<groupId>com\\.example\\.kairo</groupId>\\s*"
                    + "<artifactId>[^<]+</artifactId>\\s*<version>([^<]+)</version>");

    private static final List<String> SHADED_MODULES = List.of(
            "kairo-cli", "kairo-mcp", "kairo-ops", "kairo-attach-cli",
            "kairo-agent-bootstrap", "kairo-agent-core-modern");

    @Test
    void reactorUsesOneCifriendlyRevisionFamily() throws IOException {
        Path root = reactorRoot();
        String rootPom = Files.readString(root.resolve("pom.xml"));

        // Root project version is the CI-friendly ${revision} property, not a hard-coded literal.
        assertThat(rootPom).contains("<version>${revision}</version>");
        // The developer default is 1.7.0-SNAPSHOT (release/RC builds override with -Drevision=...).
        assertThat(rootPom).contains("<revision>1.7.0-SNAPSHOT</revision>");

        List<String> modules = moduleNames(rootPom);
        assertThat(modules).isNotEmpty();

        for (String module : modules) {
            Path childPom = root.resolve(module).resolve("pom.xml");
            assertThat(childPom).exists();
            String pom = Files.readString(childPom);

            // The child's inherited parent version is ${revision}, never the legacy 0.1.0-SNAPSHOT.
            assertThat(pom).contains("<version>${revision}</version>");
            assertThat(pom).doesNotContain("<version>0.1.0-SNAPSHOT</version>");
            // Every internal com.example.kairo dependency resolves to the reactor project version.
            for (String depVersion : internalDependencyVersions(pom)) {
                assertThat(depVersion).isEqualTo("${project.version}");
            }
        }

        // No POM anywhere in the reactor retains the legacy 0.1.0-SNAPSHOT literal.
        for (Path pom : allReactorPoms(root, modules)) {
            assertThat(Files.readString(pom)).doesNotContain("0.1.0-SNAPSHOT");
        }
    }

    @Test
    void shadedExecutablesConfigureImplementationVersion() throws IOException {
        Path root = reactorRoot();
        // The packaged manifest Implementation-Version is configured for every shaded executable so the
        // build-version resolver reads the reactor version from the packaged artifact.
        for (String module : SHADED_MODULES) {
            Path pom = root.resolve(module).resolve("pom.xml");
            assertThat(pom).exists();
            assertThat(Files.readString(pom)).contains("Implementation-Version");
        }
    }

    private static Path reactorRoot() {
        Path cur = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 12 && cur != null; i++) {
            if (Files.isRegularFile(cur.resolve("pom.xml"))
                    && containsModule(cur.resolve("pom.xml"), "kairo-integration-tests")) {
                return cur;
            }
            cur = cur.getParent();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static boolean containsModule(Path rootPom, String module) {
        try {
            return Files.readString(rootPom).contains("<module>" + module + "</module>");
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> moduleNames(String rootPom) {
        List<String> modules = new ArrayList<>();
        Matcher matcher = MODULE_PATTERN.matcher(rootPom);
        while (matcher.find()) {
            modules.add(matcher.group(1));
        }
        return modules;
    }

    private static List<String> internalDependencyVersions(String pom) {
        List<String> versions = new ArrayList<>();
        Matcher matcher = INTERNAL_DEP_PATTERN.matcher(pom);
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return versions;
    }

    private static List<Path> allReactorPoms(Path root, List<String> modules) {
        List<Path> poms = new ArrayList<>();
        poms.add(root.resolve("pom.xml"));
        for (String module : modules) {
            poms.add(root.resolve(module).resolve("pom.xml"));
        }
        return poms;
    }
}
