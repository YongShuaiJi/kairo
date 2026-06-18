package com.example.runtimemock.control;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlServerOptionsTest {

    @Test
    void parsesOptionsWithDefaults() {
        ControlServerOptions options = ControlServerOptions.parse(new String[]{"--port", "19090"});

        assertThat(options.host()).isEqualTo("127.0.0.1");
        assertThat(options.port()).isEqualTo(19090);
        assertThat(options.defaultAgent().toString()).isEqualTo("http://127.0.0.1:18080");
        assertThat(options.controlToken()).isNotBlank();
    }
}
