package com.example.kairo.platform.freeze;

import com.example.kairo.agent.server.protocol.AgentProtocolInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M0 / &sect;3.4: the Agent-protocol freeze gate. Compare-only. Freezes the actual wire JSON of
 * the authoritative {@code AgentCommandEnvelope} (platform&rarr;agent poll) and {@code AgentCommandAck}
 * (agent&rarr;platform ack) DTOs -- not DB columns -- plus the advertised {@code v1} protocol version
 * and capability set, and the capability-negotiation invariant.
 *
 * <p>No frozen capability or envelope/ack JSON field may change; additive new capabilities are allowed
 * only under the negotiation rule (old agents ignore unknown capabilities). A V1.6 fixture (the
 * capability set a V1.6 agent advertises via {@link AgentProtocolInfo#defaultV1()} at the baseline
 * {@code 113823b}) is used to assert additive compatibility.
 */
class AgentProtocolFreezeTest {

    private static final String BASELINE = "v1.7/agent-protocol-v1.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void frozenAgentProtocolIsCompatible() throws Exception {
        FreezeModels.FrozenProtocol current = FreezeCollectors.collectProtocol();
        FreezeModels.FrozenProtocol baseline =
                FreezeBaselineSupport.readBaseline(BASELINE, FreezeModels.FrozenProtocol.class);
        AgentProtocolInfo info = AgentProtocolInfo.defaultV1();
        Set<String> currentCaps = new TreeSet<>(current.capabilities());

        List<String> violations = new ArrayList<>();

        // protocol version "v1" must remain advertised.
        if (!current.protocolVersions().contains("v1")) {
            violations.add("PROTOCOL VERSION v1 REMOVED: " + current.protocolVersions());
        }
        for (String v : baseline.protocolVersions()) {
            if (!current.protocolVersions().contains(v)) {
                violations.add("FROZEN PROTOCOL VERSION REMOVED: " + v);
            }
        }

        // capability floor: no advertised capability removed (additive only if negotiation holds).
        for (String cap : baseline.capabilities()) {
            if (!currentCaps.contains(cap)) {
                violations.add("FROZEN CAPABILITY REMOVED: " + cap
                        + " (removing a command breaks older Platform/Agent combinations)");
            }
        }
        List<String> newCaps = new ArrayList<>();
        for (String cap : current.capabilities()) {
            if (!baseline.capabilities().contains(cap)) {
                newCaps.add(cap);
            }
        }
        if (!newCaps.isEmpty()) {
            System.out.println("[freeze] new agent capabilities since baseline (additive only if "
                    + "negotiation rule holds; old agents must ignore unknown capabilities): " + newCaps);
        }

        // the actual wire JSON of the command envelope and ack must be byte-stable.
        if (!baseline.commandEnvelopeJson().equals(current.commandEnvelopeJson())) {
            violations.add("COMMAND ENVELOPE WIRE JSON CHANGED (breaking the agent wire contract)");
        }
        if (!baseline.ackedAckJson().equals(current.ackedAckJson())) {
            violations.add("ACKED ACK WIRE JSON CHANGED (breaking the agent wire contract)");
        }
        if (!baseline.failedAckJson().equals(current.failedAckJson())) {
            violations.add("FAILED ACK WIRE JSON CHANGED (breaking the agent wire contract)");
        }
        if (!baseline.capabilityFailureAckJson().equals(current.capabilityFailureAckJson())) {
            violations.add("CAPABILITY FAILURE ACK WIRE JSON CHANGED (breaking the agent wire contract)");
        }

        // negotiation invariant: supports() accepts every frozen capability and rejects unknown.
        for (String cap : baseline.capabilities()) {
            if (!info.supports(cap)) {
                violations.add("NEGOTIATION INVARIANT BROKEN: supports() rejects frozen capability " + cap);
            }
        }
        if (info.supports("__v17_freeze_unknown_capability_probe__")) {
            violations.add("NEGOTIATION INVARIANT BROKEN: supports() accepts an unknown capability");
        }

        // V1.6 fixture: the agent advertises exactly the V1 capability set (single source of truth).
        assertThat(info.capabilities())
                .as("AgentProtocolInfo.defaultV1() must equal the shared KairoCommandCapabilities.V1")
                .containsExactlyInAnyOrderElementsOf(
                        com.example.kairo.api.protocol.KairoCommandCapabilities.V1);

        assertThat(violations)
                .as("Frozen Agent protocol v1 (V1.6.0 / 113823b) must remain wire- and "
                        + "capability-compatible.")
                .isEmpty();
    }
}
