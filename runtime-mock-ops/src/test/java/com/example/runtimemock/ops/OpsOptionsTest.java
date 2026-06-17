package com.example.runtimemock.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpsOptionsTest {

    @Test
    void parsesStatusDefaults() {
        OpsOptions options = OpsOptions.parse(new String[]{"status"});

        assertThat(options.command()).isEqualTo("status");
        assertThat(options.baseUrl().toString()).isEqualTo("http://127.0.0.1:18080");
    }

    @Test
    void requiresReasonForHighRiskCommands() {
        assertThatThrownBy(() -> OpsOptions.parse(new String[]{"reset-all"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--reason");
    }

    @Test
    void parsesDisableRule() {
        OpsOptions options = OpsOptions.parse(new String[]{
                "disable-rule",
                "--url", "http://127.0.0.1:18081",
                "--token", "dev",
                "--rule-id", "rule-1"
        });

        assertThat(options.command()).isEqualTo("disable-rule");
        assertThat(options.baseUrl().toString()).isEqualTo("http://127.0.0.1:18081");
        assertThat(options.token()).isEqualTo("dev");
        assertThat(options.ruleId()).isEqualTo("rule-1");
    }
}
