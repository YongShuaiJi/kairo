package com.example.kairo.platform.freeze;

import com.example.kairo.api.config.KairoConfigCatalog;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.kairo.api.config.KairoConfigCatalog.Channel.ENVIRONMENT;
import static com.example.kairo.api.config.KairoConfigCatalog.Channel.SPRING_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage guard connecting the explicit catalog to real component bindings. Scanning is
 * deliberately secondary: the typed catalog owns semantics; this test prevents a consumer from
 * adding an uncatalogued key or leaving a catalog entry with no real binding.
 */
class ConfigCatalogCoverageTest {

    private static final Pattern ENV = Pattern.compile(
            "(?<![A-Z0-9_])(KAIRO_[A-Z0-9_]+)(?![A-Z0-9_])");
    private static final Pattern SPRING_PLACEHOLDER = Pattern.compile(
            "\\$\\{((?:kairo|management)\\.[^}:]+):([^}]*)}");
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile(
            "\\$\\{(KAIRO_[A-Z0-9_]+)(?::([^}]*))?}");

    @Test
    void everyComponentBindingIsCataloguedAndEveryCatalogEntryIsUsed() throws Exception {
        assertEnvironmentCatalogMatches(repositoryRoot());
    }

    static void assertEnvironmentCatalogMatches(Path root) throws Exception {
        Map<String, List<Path>> sources = Map.of(
                "platform", List.of(root.resolve("kairo-platform-server/src/main/resources/application.yml")),
                "sidecar", List.of(root.resolve("kairo-sidecar/src/main/java")),
                "cli", List.of(root.resolve("kairo-cli/src/main/java")),
                "mcp", List.of(root.resolve("kairo-mcp/src/main/java")),
                "web", List.of(root.resolve("kairo-platform-web/.env.example"),
                        root.resolve("kairo-platform-web/app"), root.resolve("kairo-platform-web/lib"),
                        root.resolve("kairo-platform-web/playwright.config.ts")),
                "smoke", List.of(root.resolve("scripts/platform-smoke.py")));

        for (Map.Entry<String, List<Path>> component : sources.entrySet()) {
            Set<String> discovered = scan(component.getValue(), ENV);
            Set<String> catalogued = new HashSet<>();
            KairoConfigCatalog.entries().stream()
                    .filter(binding -> binding.channel() == ENVIRONMENT)
                    .filter(binding -> binding.component().equals(component.getKey()))
                    .map(KairoConfigCatalog.Binding::key)
                    .forEach(catalogued::add);
            assertThat(catalogued)
                    .as(component.getKey() + " explicit environment catalog must match real bindings")
                    .containsExactlyInAnyOrderElementsOf(discovered);
        }

        Set<String> deploymentVariables = scan(List.of(root.resolve("docker-compose.yml"),
                root.resolve("docker-compose.attach.yml")), ENV);
        Set<String> allCataloguedEnvironmentKeys = new HashSet<>();
        KairoConfigCatalog.entries().stream()
                .filter(binding -> binding.channel() == ENVIRONMENT)
                .map(KairoConfigCatalog.Binding::key)
                .forEach(allCataloguedEnvironmentKeys::add);
        assertThat(allCataloguedEnvironmentKeys)
                .as("compose files may reference only public catalogued environment variables")
                .containsAll(deploymentVariables);
    }

    @Test
    void springYamlAndDirectBindingsMatchCatalogDefaults() throws Exception {
        assertSpringCatalogMatches(repositoryRoot());
    }

    static void assertSpringCatalogMatches(Path root) throws Exception {
        Path yamlPath = root.resolve("kairo-platform-server/src/main/resources/application.yml");
        String yamlText = Files.readString(yamlPath);
        Map<String, Object> yaml = new Yaml().load(yamlText);
        Map<String, String> leaves = new HashMap<>();
        flatten("kairo", asMap(yaml.get("kairo")), leaves);
        flatten("management", asMap(yaml.get("management")), leaves);

        for (Map.Entry<String, String> leaf : leaves.entrySet()) {
            KairoConfigCatalog.Binding binding = KairoConfigCatalog.require(
                    SPRING_PROPERTY, "platform", leaf.getKey());
            assertDefaultMatches(binding, leaf.getValue());
        }

        List<Path> platformJava = List.of(root.resolve("kairo-platform-server/src/main/java"));
        Map<String, String> directDefaults = scanDefaults(platformJava, SPRING_PLACEHOLDER);
        for (Map.Entry<String, String> direct : directDefaults.entrySet()) {
            KairoConfigCatalog.Binding binding = KairoConfigCatalog.require(
                    SPRING_PROPERTY, "platform", direct.getKey());
            if (!binding.sensitive()) {
                assertThat(binding.defaultValue())
                        .as("direct Spring default for " + direct.getKey())
                        .isEqualTo(direct.getValue());
            }
        }

        Set<String> discoveredSpringKeys = new HashSet<>(leaves.keySet());
        discoveredSpringKeys.addAll(directDefaults.keySet());
        Set<String> cataloguedSpringKeys = new HashSet<>();
        KairoConfigCatalog.entries().stream()
                .filter(binding -> binding.channel() == SPRING_PROPERTY)
                .filter(binding -> binding.component().equals("platform"))
                .map(KairoConfigCatalog.Binding::key)
                .forEach(cataloguedSpringKeys::add);
        assertThat(cataloguedSpringKeys)
                .as("Spring catalog must have neither missing nor dead bindings")
                .containsExactlyInAnyOrderElementsOf(discoveredSpringKeys);

        Matcher envMatcher = ENV_PLACEHOLDER.matcher(yamlText);
        while (envMatcher.find()) {
            String key = envMatcher.group(1);
            String defaultValue = envMatcher.group(2);
            KairoConfigCatalog.Binding binding = KairoConfigCatalog.require(
                    ENVIRONMENT, "platform", key);
            assertThat(binding.defaultPresent()).isEqualTo(defaultValue != null);
            if (!binding.sensitive()) {
                assertThat(binding.defaultValue()).as("YAML environment default for " + key)
                        .isEqualTo(defaultValue == null ? "" : defaultValue);
            }
        }
    }

    private static void assertDefaultMatches(KairoConfigCatalog.Binding binding, String raw) {
        Matcher placeholder = ENV_PLACEHOLDER.matcher(raw);
        String effective = placeholder.matches() ? placeholder.group(2) : raw;
        boolean present = !placeholder.matches() || effective != null;
        assertThat(binding.defaultPresent()).isEqualTo(present);
        if (!binding.sensitive()) {
            assertThat(binding.defaultValue()).as("Spring default for " + binding.key())
                    .isEqualTo(effective == null ? "" : effective);
        }
    }

    private static Map<String, String> scanDefaults(List<Path> roots, Pattern pattern) throws IOException {
        Map<String, String> result = new HashMap<>();
        for (Path file : files(roots)) {
            Matcher matcher = pattern.matcher(Files.readString(file));
            while (matcher.find()) {
                String previous = result.put(matcher.group(1), matcher.group(2));
                if (previous != null && !previous.equals(matcher.group(2))) {
                    throw new IllegalStateException("Conflicting defaults for " + matcher.group(1));
                }
            }
        }
        return result;
    }

    private static Set<String> scan(List<Path> roots, Pattern pattern) throws IOException {
        Set<String> result = new HashSet<>();
        for (Path file : files(roots)) {
            Matcher matcher = pattern.matcher(Files.readString(file));
            while (matcher.find()) {
                result.add(matcher.group(1));
            }
        }
        return result;
    }

    private static List<Path> files(List<Path> roots) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isRegularFile(root)) {
                result.add(root);
            } else if (Files.isDirectory(root)) {
                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> !path.toString().contains("/target/"))
                            .filter(path -> !path.toString().contains("/node_modules/"))
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void flatten(String prefix, Map<String, Object> map, Map<String, String> out) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flatten(key, asMap(nested), out);
            } else {
                out.put(key, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
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
}
