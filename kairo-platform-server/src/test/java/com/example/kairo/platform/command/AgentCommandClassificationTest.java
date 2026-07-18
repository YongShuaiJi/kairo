package com.example.kairo.platform.command;

import com.example.kairo.api.protocol.KairoCommandCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 &sect;4.2 fixed command classification: every V1 capability is exactly one of DURABLE
 * or TRANSIENT, and the split matches the plan's enumeration verbatim. The classification is
 * a frozen contract surface, so this test guards both the partition and each family.
 */
class AgentCommandClassificationTest {

    @Test
    void everyDurableCommandIsClassifiedDurable() {
        for (String type : AgentCommandClassification.DURABLE) {
            assertThat(AgentCommandClassification.isDurable(type))
                    .as("DURABLE command %s must be durable", type).isTrue();
            assertThat(AgentCommandClassification.isTransient(type))
                    .as("DURABLE command %s must not be transient", type).isFalse();
        }
    }

    @Test
    void durableSetMatchesPlanExactly() {
        // §4.2 enumerates exactly these eight DURABLE commands.
        assertThat(AgentCommandClassification.DURABLE).containsExactlyInAnyOrder(
                "APPLY_RULE", "APPLY_CHAIN", "DISABLE_ALL", "ENABLE_ALL",
                "RESET_CLASS", "RESET_ALL", "STOP_AGENT", "REFRESH_RUNTIME_STATE"
        );
    }

    @Test
    void transientExplicitCommandsAreClassifiedTransient() {
        for (String type : new String[]{
                "START_RECORDING", "STOP_RECORDING",
                "DISCOVER_TARGETS", "LIST_LOADERS", "LIST_CALL_SITES", "RESOLVE_TARGET"
        }) {
            assertThat(AgentCommandClassification.isTransient(type))
                    .as("explicit TRANSIENT command %s", type).isTrue();
            assertThat(AgentCommandClassification.isDurable(type))
                    .as("explicit TRANSIENT command %s must not be durable", type).isFalse();
        }
    }

    @Test
    void everyBytecodeFamilyCommandIsTransient() {
        for (String type : new String[]{
                "BYTECODE_TRANSFORMATIONS", "BYTECODE_GET", "BYTECODE_PREVIEW",
                "BYTECODE_CAPTURE", "BYTECODE_DIFF", "BYTECODE_SOMETHING_NEW"
        }) {
            assertThat(AgentCommandClassification.isTransient(type))
                    .as("BYTECODE_* command %s", type).isTrue();
            assertThat(AgentCommandClassification.isDurable(type)).isFalse();
        }
    }

    @Test
    void everyScriptFamilyCommandIsTransient() {
        for (String type : new String[]{
                "SCRIPT_SESSION_CREATE", "SCRIPT_SESSION_VALIDATE", "SCRIPT_SESSION_APPLY",
                "SCRIPT_SESSION_PROMOTE", "SCRIPT_SESSION_REVERT", "SCRIPT_COMPILE",
                "SCRIPT_SESSION_NEW_KIND"
        }) {
            assertThat(AgentCommandClassification.isTransient(type))
                    .as("SCRIPT_* command %s", type).isTrue();
            assertThat(AgentCommandClassification.isDurable(type)).isFalse();
        }
    }

    @Test
    void everyV1CapabilityIsExactlyOneClass() {
        // §4.2: the fixed classification partitions the frozen V1 capability set.
        for (String capability : KairoCommandCapabilities.V1) {
            boolean durable = AgentCommandClassification.isDurable(capability);
            boolean transientCmd = AgentCommandClassification.isTransient(capability);
            assertThat(durable ^ transientCmd)
                    .as("V1 capability %s must be exactly one of DURABLE/TRANSIENT "
                            + "(durable=%s, transient=%s)", capability, durable, transientCmd)
                    .isTrue();
        }
    }

    @Test
    void transientContextLostCodeIsFixed() {
        // §8.2#5: the fixed machine-readable failure code for orphan TRANSIENT recovery.
        assertThat(AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE)
                .isEqualTo("TRANSIENT_COMMAND_CONTEXT_LOST");
    }

    @Test
    void blankAndUnknownTypesAreNeither() {
        // Unknown command types cannot be enqueued (capability gate); the recovery leaves
        // them alone rather than guessing - fail-closed classification, not optimistic.
        assertThat(AgentCommandClassification.isDurable(null)).isFalse();
        assertThat(AgentCommandClassification.isTransient(null)).isFalse();
        assertThat(AgentCommandClassification.isDurable("")).isFalse();
        assertThat(AgentCommandClassification.isTransient("")).isFalse();
        assertThat(AgentCommandClassification.isDurable("UNKNOWN_FUTURE_COMMAND")).isFalse();
        assertThat(AgentCommandClassification.isTransient("UNKNOWN_FUTURE_COMMAND")).isFalse();
    }
}
