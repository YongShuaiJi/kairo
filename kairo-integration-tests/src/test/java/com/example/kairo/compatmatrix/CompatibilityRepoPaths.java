package com.example.kairo.compatmatrix;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves committed repository files (the compatibility workflow, the acceptance
 * manifest and the generated compatibility document) relative to the V1.7 repository
 * root. The root is located by walking up from the JVM working directory until a directory
 * containing {@code v1.7-acceptance-manifest.json} is found, so the tests are robust
 * whether surefire runs from a module basedir or the reactor root.
 *
 * <p>Test-only helper; never packaged.
 */
final class CompatibilityRepoPaths {

    private CompatibilityRepoPaths() {
    }

    /** The V1.7 repository root (contains {@code v1.7-acceptance-manifest.json}). */
    static Path repoRoot() {
        Path cur = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 12 && cur != null; i++) {
            if (Files.isRegularFile(cur.resolve("v1.7-acceptance-manifest.json"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        // Fallback: assume the working directory is the repository root.
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    /** The committed generated compatibility document. */
    static Path committedDocument() {
        return repoRoot().resolve("docs/compatibility/v1.7.md");
    }

    /** The V1.7 acceptance manifest. */
    static Path acceptanceManifest() {
        return repoRoot().resolve("v1.7-acceptance-manifest.json");
    }

    /** The authoritative compatibility-matrix workflow. */
    static Path workflow() {
        return repoRoot().resolve(".github/workflows/compatibility-matrix.yml");
    }
}
