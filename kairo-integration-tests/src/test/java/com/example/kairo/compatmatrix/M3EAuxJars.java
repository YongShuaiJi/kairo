package com.example.kairo.compatmatrix;

import java.nio.file.Path;

/**
 * Resolved auxiliary jars the M3-E executor passes onto the independent target JVM
 * classpath. C10 needs the real Byte Buddy library on the controlled Byte Buddy Agent's
 * runtime classpath (genuine {@code net.bytebuddy} {@code AgentBuilder}/{@code Advice});
 * C08 (redefine/retransform/hot-update drift) is pure Java and uses no auxiliary jar
 * (its harness agent captures {@link java.lang.instrument.Instrumentation} directly).
 *
 * <p>Resolved by {@link M3EScenarioDispatch} from system properties provisioned by
 * {@code run-compatibility.sh} (the runner's resolved dependency classpath). A {@code null}
 * path means the jar was not provisioned; the C10 gate then fails closed rather than
 * faking the Byte Buddy transform by a class-name marker.
 */
final class M3EAuxJars {

    /** System property carrying the resolved byte-buddy jar path (shared with the M3-D C06 gate). */
    static final String BYTE_BUDDY_JAR_PROPERTY = "kairo.compat.artifacts.byteBuddyJar";

    final Path byteBuddyJar;   // net.bytebuddy:byte-buddy jar (C10 controlled BB Agent)

    private M3EAuxJars(Path byteBuddyJar) {
        this.byteBuddyJar = byteBuddyJar;
    }

    /** Resolves the auxiliary jars from the system properties; paths may be null. */
    static M3EAuxJars fromProperties() {
        return new M3EAuxJars(pathProp(BYTE_BUDDY_JAR_PROPERTY));
    }

    /** A no-aux placeholder for C08 (pure Java, no external jar). */
    static M3EAuxJars none() {
        return new M3EAuxJars(null);
    }

    private static Path pathProp(String name) {
        String v = System.getProperty(name);
        return (v == null || v.isBlank()) ? null : Path.of(v);
    }
}
