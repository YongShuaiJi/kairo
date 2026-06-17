package com.example.runtimemock.attach;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AttachOptionsTest {

    @Test
    void parsesAttachOptions() throws Exception {
        Path jar = Files.createTempFile("runtime-mock-agent", ".jar");
        Path coreJar = Files.createTempFile("runtime-mock-agent-core", ".jar");
        Path bootstrapJar = Files.createTempFile("runtime-mock-bootstrap-api", ".jar");

        AttachOptions options = AttachOptions.parse(new String[]{
                "--pid", "12345",
                "--agent", jar.toString(),
                "--core-jar", coreJar.toString(),
                "--bootstrap-jar", bootstrapJar.toString(),
                "--port", "19090",
                "--token", "dev",
                "--platform-url", "http://127.0.0.1:18280",
                "--platform-agent-id", "agent-1",
                "--platform-token", "platform-dev"
        });

        assertThat(options.pid()).isEqualTo("12345");
        assertThat(options.agentArgs()).isEqualTo("attach=true,host=127.0.0.1,port=19090,token=dev"
                + ",coreJar=" + coreJar.toAbsolutePath().normalize()
                + ",bootstrapJar=" + bootstrapJar.toAbsolutePath().normalize()
                + ",platformUrl=http://127.0.0.1:18280"
                + ",platformAgentId=agent-1"
                + ",platformToken=platform-dev");
    }
}
