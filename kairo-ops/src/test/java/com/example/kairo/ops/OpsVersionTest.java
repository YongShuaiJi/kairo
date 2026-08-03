package com.example.kairo.ops;

import com.example.kairo.api.build.KairoBuildVersion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M5-A &sect;12.1: the {@code kairo-ops --version} surface works without credentials or network
 * and reports the same packaged project version as the shared {@link KairoBuildVersion} resolver.
 */
class OpsVersionTest {

    @Test
    void versionFlagReportsSharedBuildVersionWithoutCredentials() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = OpsCommand.execute(new String[]{"--version"}, new PrintStream(out), new PrintStream(err));

        assertThat(exit).isZero();
        assertThat(err.toString()).isEmpty();
        assertThat(out.toString().trim()).isEqualTo("kairo-ops " + KairoBuildVersion.resolve());
    }
}
