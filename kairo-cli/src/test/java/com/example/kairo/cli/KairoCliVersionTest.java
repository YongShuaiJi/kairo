package com.example.kairo.cli;

import com.example.kairo.api.build.KairoBuildVersion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M5-A &sect;12.1: the {@code kairo-cli --version} surface works without credentials or network
 * and reports the same packaged project version as the shared {@link KairoBuildVersion} resolver.
 */
class KairoCliVersionTest {

    @Test
    void versionFlagReportsSharedBuildVersionWithoutCredentials() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        KairoCli cli = new KairoCli(new PrintStream(out), new PrintStream(err),
                Path.of(System.getProperty("java.io.tmpdir"), "kairo-cli-version-test-creds"));

        int exit = cli.run(new String[]{"--version"});

        assertThat(exit).isZero();
        assertThat(err.toString()).isEmpty();
        String line = out.toString().trim();
        assertThat(line).startsWith("kairo-cli ");
        assertThat(line).endsWith(KairoBuildVersion.resolve());
        assertThat(line).isEqualTo("kairo-cli " + KairoBuildVersion.resolve());
    }
}
