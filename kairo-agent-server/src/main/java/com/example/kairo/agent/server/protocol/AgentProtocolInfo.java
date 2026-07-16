package com.example.kairo.agent.server.protocol;

import com.example.kairo.api.protocol.KairoCommandCapabilities;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strongly-typed agent protocol advertisement (V1.6 &sect;5.2). The agent reports
 * the protocol versions it speaks and the command capabilities it handles; the
 * platform uses this to avoid dispatching commands the agent cannot execute.
 *
 * <p>The V1 capability set is the shared, frozen {@link KairoCommandCapabilities#V1}
 * (single source of truth used by both the agent and the platform dispatch gate).
 *
 * @param protocolVersions protocol versions supported, e.g. {@code ["v1"]}
 * @param capabilities     command types the agent handles
 */
public record AgentProtocolInfo(List<String> protocolVersions, Set<String> capabilities) {

    public AgentProtocolInfo {
        Objects.requireNonNull(protocolVersions, "protocolVersions");
        Objects.requireNonNull(capabilities, "capabilities");
        protocolVersions = List.copyOf(protocolVersions);
        capabilities = Set.copyOf(capabilities);
    }

    /** Whether the agent advertises the given command capability. */
    public boolean supports(String commandType) {
        return commandType != null && capabilities.contains(commandType);
    }

    /** The default V1 capability set advertised by the agent. */
    public static AgentProtocolInfo defaultV1() {
        return new AgentProtocolInfo(List.of("v1"), KairoCommandCapabilities.V1);
    }

    /**
     * The current V1.7 advertisement. The strict marker opts this agent into exact capability
     * negotiation while {@link #defaultV1()} remains the frozen V1.6 compatibility fixture.
     */
    public static AgentProtocolInfo currentV17() {
        Set<String> capabilities = new LinkedHashSet<>(KairoCommandCapabilities.V1);
        capabilities.add(KairoCommandCapabilities.STRICT_NEGOTIATION);
        return new AgentProtocolInfo(List.of("v1"), capabilities);
    }
}
