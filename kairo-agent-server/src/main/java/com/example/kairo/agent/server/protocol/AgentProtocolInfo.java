package com.example.kairo.agent.server.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strongly-typed agent protocol advertisement (V1.6 &sect;5.2). The agent reports
 * the protocol versions it speaks and the command capabilities it handles; the
 * platform uses this to avoid dispatching commands the agent cannot execute.
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
        return new AgentProtocolInfo(
                List.of("v1"),
                Set.of(
                        "APPLY_RULE", "APPLY_CHAIN", "DISABLE_ALL", "ENABLE_ALL",
                        "RESET_CLASS", "RESET_ALL", "STOP_AGENT",
                        "START_RECORDING", "STOP_RECORDING",
                        "DISCOVER_TARGETS", "LIST_LOADERS", "LIST_CALL_SITES",
                        "RESOLVE_TARGET", "REFRESH_RUNTIME_STATE",
                        "BYTECODE_TRANSFORMATIONS", "BYTECODE_GET", "BYTECODE_PREVIEW",
                        "BYTECODE_CAPTURE", "BYTECODE_DIFF",
                        "SCRIPT_SESSION_CREATE", "SCRIPT_SESSION_VALIDATE",
                        "SCRIPT_SESSION_APPLY", "SCRIPT_SESSION_PROMOTE",
                        "SCRIPT_SESSION_REVERT", "SCRIPT_COMPILE"
                ));
    }
}
