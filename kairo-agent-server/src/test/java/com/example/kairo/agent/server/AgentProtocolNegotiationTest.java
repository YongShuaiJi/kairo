package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.server.protocol.AgentProtocolInfo;
import com.example.kairo.agent.server.protocol.CapabilityNotSupportedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.6 &sect;5.2 / &sect;8 capability negotiation: the agent advertises protocol
 * versions + capabilities, and an unadvertised command yields a structured
 * {@link CapabilityNotSupportedException} (surfaced as a CAPABILITY_NOT_SUPPORTED
 * ack) rather than a crash.
 */
class AgentProtocolNegotiationTest {

    private AgentRuntime runtime;
    private PlatformCommandPoller poller;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        poller = new PlatformCommandPoller(runtime, AgentLaunchConfig.parse(""), () -> { });
    }

    @AfterEach
    void tearDown() {
        if (poller != null) {
            poller.close();
        }
        runtime.close();
    }

    @Test
    void defaultV1AdvertisesProtocolVersionsAndCapabilities() {
        AgentProtocolInfo info = AgentProtocolInfo.defaultV1();
        assertThat(info.protocolVersions()).contains("v1");
        assertThat(info.capabilities()).contains("APPLY_RULE", "DISCOVER_TARGETS", "SCRIPT_SESSION_CREATE");
        assertThat(info.supports("APPLY_RULE")).isTrue();
        assertThat(info.supports("UNKNOWN_FUTURE_COMMAND")).isFalse();
    }

    @Test
    void unadvertisedCommandThrowsCapabilityNotSupported() throws Exception {
        JsonNode command = mapper.readTree("""
                {"id":"cmd-1","payload":{"commandType":"UNKNOWN_FUTURE_COMMAND"}}
                """);
        assertThatThrownBy(() -> poller.execute(command))
                .isInstanceOf(CapabilityNotSupportedException.class)
                .satisfies(ex -> assertThat(((CapabilityNotSupportedException) ex).commandType())
                        .isEqualTo("UNKNOWN_FUTURE_COMMAND"));
    }
}
