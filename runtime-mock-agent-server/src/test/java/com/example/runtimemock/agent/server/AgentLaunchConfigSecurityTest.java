package com.example.runtimemock.agent.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLaunchConfigSecurityTest {

    @Test
    void generatesTokenWhenNotProvided() {
        AgentLaunchConfig config = AgentLaunchConfig.parse("");

        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.token()).isNotBlank();
        assertThat(config.token()).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    void rejectsNonLoopbackHost() {
        AgentLaunchConfig config = AgentLaunchConfig.parse("host=0.0.0.0");

        assertThatThrownBy(config::host)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void supportsProjectAndApplicationNamesForAutomaticRegistration() {
        AgentLaunchConfig config = AgentLaunchConfig.parse("""
                platformUrl=http://127.0.0.1:18280,\
                platformProjectName=runtime-mock,\
                platformApplicationName=runtime-mock-demo
                """);

        assertThat(config.platformRegistrationEnabled()).isTrue();
        assertThat(config.platformProjectName()).isEqualTo("runtime-mock");
        assertThat(config.platformApplicationName()).isEqualTo("runtime-mock-demo");
        assertThat(config.platformApplicationId()).isNull();
    }
}
