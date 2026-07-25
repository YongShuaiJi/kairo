package com.example.kairo.platform.boundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers for the M1-G module-boundary and product-entrypoint inventory gates. Locates the
 * repository root and parses the Maven {@code <module>} list and inter-module {@code kairo-*}
 * dependencies without pulling in a Maven model library.
 */
final class BoundarySupport {

    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    private BoundarySupport() {
    }

    static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("kairo-platform-server"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root");
        }
        return current;
    }

    static Path rootPom() {
        return repoRoot().resolve("pom.xml");
    }

    static List<String> modules(Path pom) throws IOException {
        String text = Files.readString(pom);
        Matcher matcher = MODULE_PATTERN.matcher(text);
        List<String> modules = new ArrayList<>();
        while (matcher.find()) {
            modules.add(matcher.group(1).trim());
        }
        return modules;
    }

    static List<String> kairoDeps(String module) throws IOException {
        Path pom = repoRoot().resolve(module + "/pom.xml");
        String text = Files.readString(pom);
        Matcher blocks = DEPENDENCY_BLOCK.matcher(text);
        List<String> deps = new ArrayList<>();
        while (blocks.find()) {
            Matcher artifact = ARTIFACT_ID.matcher(blocks.group(1));
            if (artifact.find()) {
                String id = artifact.group(1).trim();
                if (id.startsWith("kairo-") && !id.equals(module)) {
                    deps.add(id);
                }
            }
        }
        return deps;
    }

    static boolean hasMainClass(String module) throws IOException {
        Path javaRoot = repoRoot().resolve(module + "/src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            return false;
        }
        try (var stream = Files.walk(javaRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(BoundarySupport::declaresMain);
        }
    }

    private static boolean declaresMain(Path file) {
        try {
            return Files.readString(file).contains("public static void main");
        } catch (IOException ignored) {
            return false;
        }
    }
}
