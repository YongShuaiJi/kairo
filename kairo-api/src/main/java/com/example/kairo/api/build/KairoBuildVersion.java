package com.example.kairo.api.build;

/**
 * V1.7 M5-A &sect;12.1: the single reusable build-version resolver. Every executable surface that must
 * report the Kairo product build version &mdash; the Agent ({@code jvmInfo}/status/registration), the
 * {@code kairo-cli}, {@code kairo-mcp} and {@code kairo-ops} {@code --version} surfaces, and the
 * support-bundle tool identity &mdash; reads the version from here so the values cannot drift from the
 * Maven reactor version.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>The packaged JAR manifest {@code Implementation-Version}, populated from the Maven
 *       {@code project.version} (the reactor {@code ${revision}}). This is the authoritative value for
 *       a packaged executable. For the shaded executables the manifest entry is configured explicitly
 *       on the {@code maven-shade-plugin} {@code ManifestResourceTransformer}.</li>
 *   <li>The deterministic {@link #FALLBACK_VERSION} ({@code 1.7.0-SNAPSHOT}) used only when the class is
 *       loaded from unpacked classes (IDE/test runs, no manifest). This is the developer default and
 *       matches the reactor {@code <revision>} property, so a test/development run reports the same
 *       family as a packaged build.</li>
 * </ol>
 *
 * <p>It never derives identity from secrets, JDBC URLs, environment identifiers, the current time or any
 * other nondeterministic value. It performs no network or file access beyond reading the already-loaded
 * package metadata.
 *
 * <p>The Platform Server ({@code /actuator/info} and the {@code kairo_platform_build_info} gauge) does
 * <em>not</em> call this resolver: it keeps sourcing its version from the existing
 * {@code KairoBuildIdentity} (Spring {@code BuildProperties}, also derived from the Maven
 * {@code project.version}). Both paths therefore report the same reactor version.
 */
public final class KairoBuildVersion {

    /**
     * Deterministic IDE/test fallback used only when no packaged {@code Implementation-Version} is
     * available. Equals the reactor {@code <revision>} developer default so unpacked and packaged
     * builds of the same family agree.
     */
    public static final String FALLBACK_VERSION = "1.7.0-SNAPSHOT";

    private KairoBuildVersion() {
    }

    /**
     * The Kairo product build version: the packaged {@code Implementation-Version} when present,
     * otherwise {@link #FALLBACK_VERSION}.
     *
     * @return a non-blank build version, never {@code null}
     */
    public static String resolve() {
        try {
            Package pkg = KairoBuildVersion.class.getPackage();
            String version = (pkg != null) ? pkg.getImplementationVersion() : null;
            if (version != null && !version.isBlank()) {
                return version;
            }
        } catch (SecurityException ignored) {
            // No permission to read package metadata: fall back deterministically.
        }
        return FALLBACK_VERSION;
    }
}
