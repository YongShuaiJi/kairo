package com.example.kairo.agent.core.matrix;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * V1.5 &sect;6: runtime-compiled fixture classes for the compatibility matrix. Several scenarios
 * need a class loaded by a non-system ClassLoader, or two loaders each defining a class with the
 * same binary name, or a genuinely redefined class with different method-body bytes. Compiling a
 * tiny source at test time (the same pattern {@code KairoAgentIntegrationTest} uses) lets the
 * matrix exercise the real agent paths rather than a mock.
 */
final class MatrixFixtures {

    private MatrixFixtures() {
    }

    /** Compile a single-class source and load it with a fresh URLClassLoader. */
    static Class<?> compileAndLoad(String binaryName, String source, String tag) throws Exception {
        Path dir = Files.createTempDirectory("matrix-fixture-" + tag);
        Path src = dir.resolve(binaryName.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("no system Java compiler available; run on a JDK not a JRE");
        }
        int rc = compiler.run(null, null, null, "-d", dir.toString(), src.toString());
        if (rc != 0) {
            throw new AssertionError("fixture compilation failed (rc=" + rc + ") for " + binaryName);
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                MatrixFixtures.class.getClassLoader());
        return Class.forName(binaryName, true, loader);
    }

    /** Read the compiled bytes of a class from a temp dir (for a real redefine with different bytes). */
    static byte[] compiledBytes(Path dir, String binaryName) throws Exception {
        Path classFile = dir.resolve(binaryName.replace('.', '/') + ".class");
        return Files.readAllBytes(classFile);
    }

    /** Compile a single-class source into a temp dir and return the dir (bytes read via {@link #compiledBytes}). */
    static Path compileToDir(String binaryName, String source, String tag) throws Exception {
        Path dir = Files.createTempDirectory("matrix-redefine-" + tag);
        Path src = dir.resolve(binaryName.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler.run(null, null, null, "-d", dir.toString(), src.toString()) != 0) {
            throw new AssertionError("redefine fixture compilation failed for " + binaryName);
        }
        return dir;
    }
}
