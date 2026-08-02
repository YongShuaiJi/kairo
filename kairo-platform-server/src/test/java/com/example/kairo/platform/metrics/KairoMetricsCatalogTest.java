package com.example.kairo.platform.metrics;

import com.example.kairo.api.operation.OperationType;
import com.example.kairo.api.protocol.KairoCommandCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-B &sect;11.2: pure unit tests for the frozen metrics contract &mdash; the ten meter names, the
 * exact allowed tag-key set per meter, the bounded value allowlists, and the {@code OTHER}-collapse
 * normalisation that prevents persisted/user-controlled strings from creating unbounded tag values.
 */
class KairoMetricsCatalogTest {

    @Test
    void exactlyTenFrozenMeterNamesAreDeclared() {
        assertThat(KairoMetricsCatalog.METER_NAMES).containsExactlyInAnyOrder(
                KairoMetricsCatalog.AGENT_ONLINE,
                KairoMetricsCatalog.AGENT_COMMAND_BACKLOG,
                KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                KairoMetricsCatalog.OPERATION_TOTAL,
                KairoMetricsCatalog.OPERATION_DURATION_SECONDS,
                KairoMetricsCatalog.RUNTIME_RULE_TARGETS,
                KairoMetricsCatalog.RECONCILE_TOTAL,
                KairoMetricsCatalog.ROLLBACK_TOTAL,
                KairoMetricsCatalog.TTL_CLEANUP_TOTAL,
                KairoMetricsCatalog.PLATFORM_BUILD_INFO);
        assertThat(KairoMetricsCatalog.METER_NAMES).hasSize(10);
    }

    @Test
    void eachMeterHasExactlyItsAllowedTagKeySet() {
        assertThat(KairoMetricsCatalog.TAGS_AGENT_ONLINE).containsExactly(KairoMetricsCatalog.TAG_STATUS);
        assertThat(KairoMetricsCatalog.TAGS_AGENT_COMMAND_BACKLOG)
                .containsExactlyInAnyOrder(KairoMetricsCatalog.TAG_STATUS, KairoMetricsCatalog.TAG_COMMAND_TYPE);
        assertThat(KairoMetricsCatalog.TAGS_AGENT_COMMAND_TOTAL)
                .containsExactlyInAnyOrder(KairoMetricsCatalog.TAG_COMMAND_TYPE, KairoMetricsCatalog.TAG_RESULT);
        assertThat(KairoMetricsCatalog.TAGS_OPERATION_TOTAL)
                .containsExactlyInAnyOrder(KairoMetricsCatalog.TAG_OPERATION_TYPE, KairoMetricsCatalog.TAG_RESULT);
        assertThat(KairoMetricsCatalog.TAGS_OPERATION_DURATION)
                .containsExactlyInAnyOrder(KairoMetricsCatalog.TAG_OPERATION_TYPE, KairoMetricsCatalog.TAG_RESULT);
        assertThat(KairoMetricsCatalog.TAGS_RUNTIME_RULE_TARGETS).containsExactly(KairoMetricsCatalog.TAG_STATE);
        assertThat(KairoMetricsCatalog.TAGS_RECONCILE_TOTAL).containsExactly(KairoMetricsCatalog.TAG_RESULT);
        assertThat(KairoMetricsCatalog.TAGS_ROLLBACK_TOTAL).containsExactly(KairoMetricsCatalog.TAG_RESULT);
        assertThat(KairoMetricsCatalog.TAGS_TTL_CLEANUP_TOTAL).containsExactly(KairoMetricsCatalog.TAG_RESULT);
        assertThat(KairoMetricsCatalog.TAGS_PLATFORM_BUILD_INFO)
                .containsExactlyInAnyOrder(KairoMetricsCatalog.TAG_VERSION, KairoMetricsCatalog.TAG_COMMIT);
    }

    @Test
    void noForbiddenTagKeyIsDeclared() {
        Set<String> forbidden = Set.of("ruleId", "agentId", "instanceId", "className", "methodName",
                "traceId", "correlationId", "username", "userId", "appId", "environmentId",
                "applicationId", "error", "exception", "script", "class", "method");
        Set<String> allowed = Set.of(KairoMetricsCatalog.TAG_STATUS, KairoMetricsCatalog.TAG_COMMAND_TYPE,
                KairoMetricsCatalog.TAG_RESULT, KairoMetricsCatalog.TAG_OPERATION_TYPE,
                KairoMetricsCatalog.TAG_STATE, KairoMetricsCatalog.TAG_VERSION, KairoMetricsCatalog.TAG_COMMIT);
        assertThat(allowed).doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    void commandTypeAllowlistIsTheFrozenV1Contract() {
        // 25 frozen V1 command types + the OTHER fallback, never a raw/class/method identifier.
        assertThat(KairoMetricsCatalog.COMMAND_TYPES).hasSize(25);
        assertThat(KairoMetricsCatalog.COMMAND_TYPES).containsExactlyElementsOf(KairoCommandCapabilities.V1);
        assertThat(KairoMetricsCatalog.COMMAND_TYPES).contains("APPLY_RULE", "RESET_CLASS", "SCRIPT_COMPILE");
        assertThat(KairoMetricsCatalog.COMMAND_TYPES).doesNotContain("OTHER");
    }

    @Test
    void operationTypeAllowlistIsTheOperationTypeEnum() {
        List<String> expected = Arrays.stream(OperationType.values()).map(OperationType::name).collect(Collectors.toList());
        assertThat(KairoMetricsCatalog.OPERATION_TYPES).containsExactlyElementsOf(expected);
    }

    @Test
    void valueAllowlistsAreFiniteAndIncludeOther() {
        assertThat(KairoMetricsCatalog.AGENT_STATUSES)
                .containsExactlyInAnyOrder("ACTIVE", "ONLINE", "STOPPING", "DISABLED", "OFFLINE",
                        KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.COMMAND_STATUSES)
                .containsExactlyInAnyOrder("PENDING", "DISPATCHED", "ACKED", "FAILED", KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.COMMAND_RESULTS)
                .containsExactlyInAnyOrder("SUCCESS", "FAILURE", "TIMEOUT", KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.OPERATION_RESULTS)
                .containsExactlyInAnyOrder("SUCCESS", "FAILURE", "CANCELLED", "TIMEOUT", KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.RULE_TARGET_STATES)
                .containsExactlyInAnyOrder("FRESH", "DRIFTED", "UNRESOLVED", KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.RECONCILE_RESULTS).contains("SUCCESS", "FAILURE", KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.ROLLBACK_RESULTS).contains("SUCCESS", "FAILURE", KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.TTL_RESULTS).contains("SUCCESS", "FAILURE", KairoMetricsCatalog.OTHER);
    }

    @Test
    void normalizeCollapsesUnknownAndNullToOther() {
        assertThat(KairoMetricsCatalog.normalize("APPLY_RULE", KairoMetricsCatalog.COMMAND_TYPES)).isEqualTo("APPLY_RULE");
        assertThat(KairoMetricsCatalog.normalize("SUCCESS", KairoMetricsCatalog.COMMAND_RESULTS)).isEqualTo("SUCCESS");
        assertThat(KairoMetricsCatalog.normalize("FRESH", KairoMetricsCatalog.RULE_TARGET_STATES)).isEqualTo("FRESH");
        // Unknown / future / user-controlled values collapse to OTHER, never passed through.
        assertThat(KairoMetricsCatalog.normalize("rule-abc-123", KairoMetricsCatalog.COMMAND_TYPES)).isEqualTo(KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.normalize("com.example.Target.compute", KairoMetricsCatalog.COMMAND_TYPES)).isEqualTo(KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.normalize("agent-host:17001", KairoMetricsCatalog.AGENT_STATUSES)).isEqualTo(KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.normalize(null, KairoMetricsCatalog.RULE_TARGET_STATES)).isEqualTo(KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.normalize("", KairoMetricsCatalog.OPERATION_TYPES)).isEqualTo(KairoMetricsCatalog.OTHER);
        assertThat(KairoMetricsCatalog.normalize("WEIRD_RESULT", KairoMetricsCatalog.COMMAND_RESULTS)).isEqualTo(KairoMetricsCatalog.OTHER);
    }
}
