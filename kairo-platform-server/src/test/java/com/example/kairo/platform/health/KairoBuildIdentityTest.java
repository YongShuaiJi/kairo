package com.example.kairo.platform.health;

import com.example.kairo.api.build.KairoBuildVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M5-A &sect;12.1: the Platform build identity reports the Maven reactor version. When
 * {@code build-info.properties} is present the version comes from {@link BuildProperties}; otherwise
 * {@link KairoBuildIdentity} falls back to the shared {@link KairoBuildVersion} resolver (the same
 * source the Agent, CLI, MCP and Ops use), so the Platform never reports "unknown" for its version and
 * cannot drift from the other surfaces.
 */
class KairoBuildIdentityTest {

    @Test
    void versionPrefersBuildProperties() {
        // Spring Boot loads build-info.properties and strips the "build." prefix before constructing
        // BuildProperties, so the internal entry key is "version".
        Properties props = new Properties();
        props.setProperty("version", "1.7.0");
        BuildProperties build = new BuildProperties(props);
        assertThat(KairoBuildIdentity.version(build)).isEqualTo("1.7.0");
    }

    @Test
    void versionFallsBackToSharedResolverWhenBuildPropertiesAbsent() {
        assertThat(KairoBuildIdentity.version(null)).isEqualTo(KairoBuildVersion.resolve());
    }

    @Test
    void versionFallsBackToSharedResolverWhenBuildVersionBlank() {
        Properties props = new Properties();
        props.setProperty("version", "   ");
        BuildProperties build = new BuildProperties(props);
        assertThat(KairoBuildIdentity.version(build)).isEqualTo(KairoBuildVersion.resolve());
    }

    @Test
    void versionFallbackIsTheMavenDeveloperDefaultNotUnknown() {
        // The M4 "unknown" fallback is gone for the version: the Platform reports the Maven reactor
        // version (the resolver dev fallback) so it matches the packaged build identity.
        assertThat(KairoBuildIdentity.version(null)).isNotEqualTo("unknown");
        assertThat(KairoBuildIdentity.version(null)).isEqualTo("1.7.0-SNAPSHOT");
    }
}
