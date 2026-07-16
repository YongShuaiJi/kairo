package com.example.kairo.platform.freeze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared support for the V1.7 M0 contract-freeze gate tests (frozen plan &sect;3 / W0).
 *
 * <p>The frozen baselines are committed JSON resources under {@code src/test/resources/v1.7/},
 * generated once from the V1.6.0 baseline (commit {@code 113823b}) by the explicit
 * {@link FreezeBaselineGeneratorTest} (gated by {@code -Dkairo.freeze.generate=true}, never run
 * by CI). The gate tests are <b>compare-only</b>: they never write, only assert that the live
 * contract is backward-compatible with the frozen baseline (additive changes allowed; removal
 * or semantic restriction fails the build).
 */
public final class FreezeBaselineSupport {

    /** Resource prefix for all committed freeze baselines. */
    public static final String V17_RESOURCE_DIR = "v1.7/";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private FreezeBaselineSupport() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Absolute path of a committed baseline resource under {@code src/test/resources/v1.7/}. */
    public static Path baselinePath(String resourceClasspathName) {
        return Paths.get("").toAbsolutePath()
                .resolve("src/test/resources").resolve(resourceClasspathName);
    }

    /** Write a baseline model (used only by the separate generator). */
    public static void writeBaseline(String resourceClasspathName, Object model) throws IOException {
        Path path = baselinePath(resourceClasspathName);
        Files.createDirectories(path.getParent());
        mapper().writeValue(path.toFile(), model);
        System.out.println("[freeze] wrote baseline: " + path);
    }

    /** Read a committed baseline JSON into the given type (used by compare-only gate tests). */
    public static <T> T readBaseline(String resourceClasspathName, Class<T> type) throws IOException {
        try (InputStream in = FreezeBaselineSupport.class.getClassLoader()
                .getResourceAsStream(resourceClasspathName)) {
            if (in == null) {
                throw new IllegalStateException("Freeze baseline missing on classpath: "
                        + resourceClasspathName + ". Generate it from the V1.6.0 baseline with "
                        + "FreezeBaselineGeneratorTest (-Dkairo.freeze.generate=true).");
            }
            return mapper().readValue(in, type);
        }
    }
}
