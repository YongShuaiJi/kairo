package com.example.kairo.platform.command;

import com.example.kairo.api.protocol.KairoCommandCapabilities;
import com.example.kairo.platform.service.PlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.7 M0 / frozen plan &sect;3.4: the platform-side capability gate, exercised for legacy,
 * strict-marked, blank-command and malformed-JSON behaviour.
 *
 * <p>Cases required by the M0 review: unchanged partial V1.6 fixture still dispatches APPLY_RULE;
 * marked strict subset rejection; legacy future-command rejection; marked supported dispatch; blank
 * command; malformed JSON fails closed; enqueue-level rejection.
 */
class CapabilityGateTest {

    private final CapabilityGate gate = new CapabilityGate();

    private static final String POST_V1_COMMAND = "V2_FUTURE_COMMAND";

    // --- legacy: null / blank / [] ---

    @Test
    void legacyNullBlankEmptyReceivesOnlyV16Commands() {
        assertThat(gate.canDispatch(null, "APPLY_RULE")).isTrue();
        assertThat(gate.canDispatch("", "APPLY_RULE")).isTrue();
        assertThat(gate.canDispatch("[]", "APPLY_RULE")).isTrue();
        assertThat(gate.canDispatch("[]", "RESET_CLASS")).isTrue();
    }

    @Test
    void legacyFutureCommandIsRejected() {
        assertThat(gate.canDispatch(null, POST_V1_COMMAND)).isFalse();
        assertThat(gate.canDispatch("[]", POST_V1_COMMAND)).isFalse();
    }

    // --- legacy: partial V1.6 capabilities (unchanged V1.6 fixture) ---

    @Test
    void legacyPartialV16CapabilitiesStillDispatchApplyRule() {
        // V1.6 allowed JAVA_METHOD plus a partial command list and still dispatched APPLY_RULE.
        // The gate must not regress this: a legacy agent (no STRICT_NEGOTIATION marker) advertising
        // [JAVA_METHOD, RESET_CLASS] still receives every frozen V1.6 command.
        String v16Partial = toJson(List.of("JAVA_METHOD", "RESET_CLASS"));
        assertThat(gate.canDispatch(v16Partial, "APPLY_RULE")).isTrue();
        assertThat(gate.canDispatch(v16Partial, "RESET_CLASS")).isTrue();
        assertThat(gate.canDispatch(v16Partial, "SCRIPT_SESSION_CREATE")).isTrue();
    }

    @Test
    void legacyPartialCapabilitiesRejectPostV1Command() {
        // A legacy agent (even with partial caps) never receives a post-V1 command.
        assertThat(gate.canDispatch(toJson(List.of("JAVA_METHOD", "RESET_CLASS")), POST_V1_COMMAND))
                .isFalse();
    }

    // --- marked (strict) ---

    @Test
    void markedAgentReceivesOnlyAdvertisedCommands() {
        String marked = toJson(List.of(KairoCommandCapabilities.STRICT_NEGOTIATION, "APPLY_RULE"));
        assertThat(gate.canDispatch(marked, "APPLY_RULE")).isTrue();
    }

    @Test
    void markedStrictSubsetRejection() {
        // Marked agent advertising only APPLY_RULE must not receive RESET_CLASS (a V1.6 command
        // it did not advertise).
        String marked = toJson(List.of(KairoCommandCapabilities.STRICT_NEGOTIATION, "APPLY_RULE"));
        assertThat(gate.canDispatch(marked, "RESET_CLASS")).isFalse();
        assertThat(gate.canDispatch(marked, POST_V1_COMMAND)).isFalse();
    }

    @Test
    void markedAgentAdvertisingNothingRejectsAll() {
        String marked = toJson(List.of(KairoCommandCapabilities.STRICT_NEGOTIATION));
        assertThat(gate.canDispatch(marked, "APPLY_RULE")).isFalse();
    }

    // --- blank command ---

    @Test
    void blankCommandIsNeverDispatched() {
        assertThat(gate.canDispatch("[]", "")).isFalse();
        assertThat(gate.canDispatch("[]", null)).isFalse();
        assertThatThrownBy(() -> gate.requireDispatchable("[]", ""))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> assertThat(((PlatformException) ex).code())
                        .isEqualTo("CAPABILITY_NOT_SUPPORTED"));
    }

    // --- malformed JSON fails closed ---

    @Test
    void malformedCapabilitiesFailsClosedNotLegacy() {
        // Malformed non-blank JSON must NOT be treated as legacy (which would allow V1.6 commands);
        // it must fail closed with a machine-readable capability error.
        assertThatThrownBy(() -> gate.canDispatch("{not-an-array}", "APPLY_RULE"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> gate.requireDispatchable("{not-an-array}", "APPLY_RULE"))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> {
                    PlatformException pe = (PlatformException) ex;
                    assertThat(pe.code()).isEqualTo("CAPABILITY_NOT_SUPPORTED");
                    assertThat(pe.category()).isEqualTo(com.example.kairo.api.error.ErrorCategory.CAPABILITY);
                    assertThat(pe.status()).isEqualTo(409);
                });
    }

    // --- enqueue-level behaviour ---

    @Test
    void requireDispatchableThrowsCapabilityNotSupportedForUnsupported() {
        // Enqueue-level: a post-V1 command to a legacy agent is rejected before dispatch.
        assertThatThrownBy(() -> gate.requireDispatchable(null, POST_V1_COMMAND))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> {
                    PlatformException pe = (PlatformException) ex;
                    assertThat(pe.code()).isEqualTo("CAPABILITY_NOT_SUPPORTED");
                    assertThat(pe.status()).isEqualTo(409);
                });
    }

    @Test
    void requireDispatchableAllowsLegacyV16Command() {
        gate.requireDispatchable(null, "APPLY_RULE");
        gate.requireDispatchable(toJson(List.of("JAVA_METHOD", "RESET_CLASS")), "APPLY_RULE");
        gate.requireDispatchable(toJson(List.of(KairoCommandCapabilities.STRICT_NEGOTIATION, "APPLY_RULE")),
                "APPLY_RULE");
    }

    @Test
    void everyV16CommandIsDispatchableToLegacyAgent() {
        for (String cap : KairoCommandCapabilities.V1) {
            assertThat(gate.canDispatch(null, cap))
                    .as("legacy agent should receive V1.6 command " + cap).isTrue();
        }
    }

    private static String toJson(java.util.Collection<String> caps) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String c : caps) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(c).append('"');
            first = false;
        }
        return sb.append(']').toString();
    }
}
