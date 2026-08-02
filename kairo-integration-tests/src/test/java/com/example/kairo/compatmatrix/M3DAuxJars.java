package com.example.kairo.compatmatrix;

import java.nio.file.Path;

/**
 * Resolved auxiliary jars the M3-D executor passes onto the independent target JVM
 * classpath. C06 needs the real Byte Buddy library (runtime subclass/proxy generation)
 * and Spring's repackaged CGLIB ({@code org.springframework.cglib.proxy.Enhancer});
 * C05 (parent/child loaders) and C07 (lambda/bridge/synthetic) are pure Java and use
 * no auxiliary jar.
 *
 * <p>Resolved by {@link M3DScenarioDispatch} from system properties provisioned by
 * {@code run-compatibility.sh} (the runner's resolved dependency classpath). A {@code null}
 * path means the jar was not provisioned; the C06 gate then fails closed rather than
 * faking the proxy by a class-name marker.
 */
final class M3DAuxJars {

    /** System property carrying the resolved byte-buddy jar path. */
    static final String BYTE_BUDDY_JAR_PROPERTY = "kairo.compat.artifacts.byteBuddyJar";
    /** System property carrying the resolved spring-core jar path (repackaged CGLIB). */
    static final String SPRING_CORE_JAR_PROPERTY = "kairo.compat.artifacts.springCoreJar";

    final Path byteBuddyJar;   // net.bytebuddy:byte-buddy jar (C06)
    final Path springCoreJar;  // org.springframework:spring-core jar (C06, repackaged cglib)

    private M3DAuxJars(Path byteBuddyJar, Path springCoreJar) {
        this.byteBuddyJar = byteBuddyJar;
        this.springCoreJar = springCoreJar;
    }

    /** Resolves the auxiliary jars from the system properties; paths may be null. */
    static M3DAuxJars fromProperties() {
        return new M3DAuxJars(pathProp(BYTE_BUDDY_JAR_PROPERTY), pathProp(SPRING_CORE_JAR_PROPERTY));
    }

    /** A no-aux placeholder for C05/C07 (pure Java, no external jar). */
    static M3DAuxJars none() {
        return new M3DAuxJars(null, null);
    }

    private static Path pathProp(String name) {
        String v = System.getProperty(name);
        return (v == null || v.isBlank()) ? null : Path.of(v);
    }
}
