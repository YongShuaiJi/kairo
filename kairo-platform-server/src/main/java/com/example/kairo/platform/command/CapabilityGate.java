package com.example.kairo.platform.command;

import com.example.kairo.api.protocol.KairoCommandCapabilities;
import com.example.kairo.platform.service.PlatformException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * V1.7 M0 / frozen plan &sect;3.4: the platform-side capability gate. Ensures an agent never
 * <em>receives</em> a command it is not allowed to execute, rejecting before dispatch with a
 * machine-readable {@code CAPABILITY_NOT_SUPPORTED}.
 *
 * <p>Semantics (V1.7 strict-negotiation marker):
 * <ul>
 *   <li><b>Legacy</b> (capabilities JSON is {@code null}, blank, {@code []}, or a non-empty set
 *       that does NOT contain {@link KairoCommandCapabilities#STRICT_NEGOTIATION}): the agent may
 *       receive only commands in the frozen V1.6 set {@link KairoCommandCapabilities#V1},
 *       <em>regardless of any partial legacy capabilities it advertised</em>. Any post-V1 command
 *       is rejected. This preserves V1.6 dispatch behaviour (V1.6 allowed e.g. {@code JAVA_METHOD}
 *       plus a partial command list and still dispatched {@code APPLY_RULE}).</li>
 *   <li><b>Marked (strict)</b> (advertised set contains {@code STRICT_NEGOTIATION}): the agent may
 *       receive only the exact command capabilities it advertised (the marker is not a dispatchable
 *       command); any other command is rejected.</li>
 *   <li><b>Malformed</b> non-blank JSON fails closed with a {@code CAPABILITY_NOT_SUPPORTED} error
 *       (it is never silently treated as legacy).</li>
 *   <li>A blank command is never dispatched.</li>
 * </ul>
 */
@Component
public class CapabilityGate {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    /**
     * Whether a command of {@code commandType} may be dispatched to an agent whose advertised
     * capabilities are the JSON array string {@code capabilitiesJson}. Throws on malformed JSON.
     */
    public boolean canDispatch(String capabilitiesJson, String commandType) {
        if (commandType == null || commandType.isBlank()) {
            return false; // blank command is never dispatched
        }
        Set<String> advertised = parseCapabilitiesOrThrow(capabilitiesJson);
        if (advertised.contains(KairoCommandCapabilities.STRICT_NEGOTIATION)) {
            // strict: exact advertised set (marker excluded)
            return advertised.contains(commandType);
        }
        // legacy: only frozen V1.6 commands, regardless of partial legacy capabilities
        return KairoCommandCapabilities.V1.contains(commandType);
    }

    /** Reject (throw) if the command cannot be dispatched to the advertised capabilities. */
    public void requireDispatchable(String capabilitiesJson, String commandType) {
        if (commandType == null || commandType.isBlank()) {
            throw PlatformException.unsupportedCapability(
                    "Cannot dispatch a blank command type.",
                    java.util.Map.of("commandType", str(commandType)));
        }
        Set<String> advertised;
        try {
            advertised = parseCapabilitiesOrThrow(capabilitiesJson);
        } catch (MalformedCapabilitiesException e) {
            throw PlatformException.unsupportedCapability(
                    "Agent advertised a malformed capabilities JSON (expected a string array); "
                            + "the platform does not dispatch to an agent whose capabilities cannot be negotiated.",
                    java.util.Map.of("capabilitiesJson", str(capabilitiesJson),
                            "reason", "malformed"));
        }
        boolean strict = advertised.contains(KairoCommandCapabilities.STRICT_NEGOTIATION);
        boolean allowed = strict
                ? advertised.contains(commandType)
                : KairoCommandCapabilities.V1.contains(commandType);
        if (!allowed) {
            throw PlatformException.unsupportedCapability(
                    strict
                            ? "Agent advertised STRICT_NEGOTIATION but did not advertise command " + commandType
                            : "Legacy agent may not receive post-V1 command " + commandType
                            + " (legacy agents may receive only frozen V1.6 commands).",
                    java.util.Map.of("commandType", commandType,
                            "strict", strict,
                            "advertisedCapabilities", advertised,
                            "legacyAllowedSet", "KairoCommandCapabilities.V1"));
        }
    }

    /** Parse the capabilities JSON; null/blank/[] => empty (legacy); malformed non-blank => throw. */
    private static Set<String> parseCapabilitiesOrThrow(String capabilitiesJson)
            throws MalformedCapabilitiesException {
        if (capabilitiesJson == null || capabilitiesJson.isBlank()) {
            return Set.of();
        }
        String trimmed = capabilitiesJson.trim();
        if ("[]".equals(trimmed)) {
            return Set.of();
        }
        try {
            List<String> list = MAPPER.readValue(trimmed, STRING_LIST);
            return new TreeSet<>(list);
        } catch (Exception e) {
            throw new MalformedCapabilitiesException();
        }
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /** Internal signal that the capabilities JSON was malformed (must fail closed, not legacy). */
    private static final class MalformedCapabilitiesException extends RuntimeException {
    }
}
