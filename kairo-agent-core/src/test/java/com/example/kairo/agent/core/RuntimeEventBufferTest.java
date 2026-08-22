package com.example.kairo.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEventBufferTest {

    @Test
    void boundsEventsAndScrubsSecretsFromMessagesAndFailures() {
        RuntimeEventBuffer buffer = new RuntimeEventBuffer(2, 200);
        buffer.record("one", "actor", null, null, "token=secret-value");
        buffer.error("failed", new IllegalStateException("password=hunter2"));
        buffer.record("three", "actor", null, null, "ok");

        assertThat(buffer.snapshot()).hasSize(2);
        assertThat(buffer.snapshot().get(0).message())
                .contains("password=[REDACTED]")
                .doesNotContain("hunter2");
        assertThat(buffer.snapshot()).noneMatch(event -> event.message().contains("secret-value"));
    }
}
