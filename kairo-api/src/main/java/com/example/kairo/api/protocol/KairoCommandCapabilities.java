package com.example.kairo.api.protocol;

import java.util.Set;

/**
 * The authoritative V1 set of Agent command capabilities (V1.7 M0 / frozen plan &sect;3.4). This
 * lives in the shared {@code kairo-api} module so both the Platform (dispatch capability gate) and
 * the Agent ({@code AgentProtocolInfo.defaultV1()}) reference one source of truth, without the
 * Platform depending on the agent implementation.
 *
 * <p>The set is a frozen contract surface: removing a capability is a breaking change guarded by
 * {@code AgentProtocolFreezeTest}. Additive new capabilities are allowed only under the negotiation
 * rule (old agents ignore unknown capabilities; the platform does not dispatch a capability before
 * negotiation confirms it).
 */
public final class KairoCommandCapabilities {

    /**
     * The frozen V1 command capability set (the V1.6.0 baseline, commit {@code 113823b}). A legacy
     * agent (one that did not advertise {@link #STRICT_NEGOTIATION}) may receive only commands in
     * this set; any post-V1 command dispatched to a legacy agent is rejected.
     */
    public static final Set<String> V1 = Set.of(
            "APPLY_RULE", "APPLY_CHAIN", "DISABLE_ALL", "ENABLE_ALL",
            "RESET_CLASS", "RESET_ALL", "STOP_AGENT",
            "START_RECORDING", "STOP_RECORDING",
            "DISCOVER_TARGETS", "LIST_LOADERS", "LIST_CALL_SITES",
            "RESOLVE_TARGET", "REFRESH_RUNTIME_STATE",
            "BYTECODE_TRANSFORMATIONS", "BYTECODE_GET", "BYTECODE_PREVIEW",
            "BYTECODE_CAPTURE", "BYTECODE_DIFF",
            "SCRIPT_SESSION_CREATE", "SCRIPT_SESSION_VALIDATE",
            "SCRIPT_SESSION_APPLY", "SCRIPT_SESSION_PROMOTE",
            "SCRIPT_SESSION_REVERT", "SCRIPT_COMPILE");

    /**
     * The V1.7 strict-negotiation marker. An agent that advertises this capability opts into
     * strict negotiation: it may receive only the exact command capabilities it advertised (the
     * marker itself is not a dispatchable command). An agent that does NOT advertise it is legacy
     * and may receive only commands in {@link #V1}, regardless of any partial legacy capabilities
     * it advertised -- preserving V1.6 dispatch behaviour.
     */
    public static final String STRICT_NEGOTIATION = "STRICT_NEGOTIATION";

    private KairoCommandCapabilities() {
    }
}
