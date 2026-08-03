package com.example.kairo.api.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M5-A &sect;12.1: the shared build-version resolver. Proves the deterministic fallback used in
 * IDE/test runs (no packaged manifest) and the packaged {@code Implementation-Version} path that the
 * shaded executables and the Agent bundle rely on. The packaged path is exercised by loading the class
 * from a real JAR built on the fly with a known manifest, so it does not depend on a prior
 * {@code mvn package}.
 */
class KairoBuildVersionTest {

    private static final String CLASS_RESOURCE =
            "com/example/kairo/api/build/KairoBuildVersion.class";

    @Test
    void fallbackIsThe1710SnapshotDeveloperDefault() {
        assertThat(KairoBuildVersion.FALLBACK_VERSION).isEqualTo("1.7.0-SNAPSHOT");
    }

    @Test
    void resolveReturnsFallbackWhenNoPackagedManifest() {
        // The test classpath runs from unpacked classes (no JAR manifest), so resolve() must fall back
        // deterministically to the developer default rather than reporting null/blank.
        String resolved = KairoBuildVersion.resolve();
        assertThat(resolved).isEqualTo(KairoBuildVersion.FALLBACK_VERSION);
        assertThat(resolved).isEqualTo("1.7.0-SNAPSHOT");
    }

    @Test
    void resolveReadsPackagedImplementationVersion(@TempDir Path tempDir) throws Exception {
        // Build a JAR on the fly containing KairoBuildVersion with a known Implementation-Version, load it
        // through an isolated URLClassLoader, and invoke resolve() reflectively. This is the only way to
        // prove the manifest path without a prior mvn package, and it mirrors how the shaded executables
        // and the Agent bundle (loaded by IsolatedAgentClassLoader) read the packaged identity.
        Path jar = tempDir.resolve("kairo-build-version-test.jar");
        writeClassJar(jar, "9.9.9-test");

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ClassLoader.getSystemClassLoader().getParent())) {
            Class<?> loaded = loader.loadClass(KairoBuildVersion.class.getName());
            // The class is physically present in both classloaders; ensure we exercised the isolated copy.
            assertThat(loaded.getClassLoader()).isSameAs(loader);
            String resolved = (String) loaded.getMethod("resolve").invoke(null);
            assertThat(resolved).isEqualTo("9.9.9-test");

            Package pkg = loaded.getPackage();
            assertThat(pkg).isNotNull();
            assertThat(pkg.getImplementationVersion()).isEqualTo("9.9.9-test");
        }
    }

    @Test
    void resolveFallsBackWhenImplementationVersionAbsent(@TempDir Path tempDir) throws Exception {
        // A packaged JAR without Implementation-Version must still resolve deterministically.
        Path jar = tempDir.resolve("kairo-build-version-noop.jar");
        writeClassJar(jar, null);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ClassLoader.getSystemClassLoader().getParent())) {
            Class<?> loaded = loader.loadClass(KairoBuildVersion.class.getName());
            String resolved = (String) loaded.getMethod("resolve").invoke(null);
            assertThat(resolved).isEqualTo(KairoBuildVersion.FALLBACK_VERSION);
        }
    }

    private static void writeClassJar(Path jar, String implementationVersion) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.putValue("Manifest-Version", "1.0");
        if (implementationVersion != null) {
            main.putValue(Attributes.Name.IMPLEMENTATION_VERSION.toString(), implementationVersion);
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            JarEntry entry = new JarEntry(CLASS_RESOURCE);
            out.putNextEntry(entry);
            try (InputStream in = KairoBuildVersionTest.class.getClassLoader().getResourceAsStream(CLASS_RESOURCE)) {
                assertThat(in).as("KairoBuildVersion.class must be on the test classpath").isNotNull();
                in.transferTo(out);
            }
            out.closeEntry();
        }
    }
}
