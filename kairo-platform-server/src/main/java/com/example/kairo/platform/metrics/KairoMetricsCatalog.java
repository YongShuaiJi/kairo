package com.example.kairo.platform.metrics;

import com.example.kairo.api.operation.OperationType;
import com.example.kairo.api.protocol.KairoCommandCapabilities;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * V1.7 M4-B &sect;11.2: the frozen business-metrics contract. This is the single source of truth for the
 * ten meter names, the exact allowed tag-key set per meter, and the finite value vocabularies (allowlists)
 * used as meter tag values. Call sites never build meters or attach tags directly; they pass raw values
 * to {@link KairoMetricsRecorder}, which normalizes every value through {@link #normalize(String, Collection)}
 * so an unrecognised (or future) value collapses into {@link #OTHER} rather than creating a new tag value.
 *
 * <p>High-cardinality identifiers (ruleId, agentId, instanceId, className, methodName, traceId,
 * correlationId, username, application/environment id, exception text, script content) are never used as
 * labels &mdash; locating a specific object is the job of structured logs and the existing resource queries.
 *
 * <p>The set of names, tag keys and value allowlists below is the V1.7 operational contract. Adding a
 * value to an allowlist is a contract change; the cardinality tests assert the exact bounded set.
 */
public final class KairoMetricsCatalog {

    private KairoMetricsCatalog() {
    }

    // ----- Meter names (frozen V1.7 §11.2) -----

    public static final String AGENT_ONLINE = "kairo_agent_online";
    public static final String AGENT_COMMAND_BACKLOG = "kairo_agent_command_backlog";
    public static final String AGENT_COMMAND_TOTAL = "kairo_agent_command_total";
    public static final String OPERATION_TOTAL = "kairo_operation_total";
    public static final String OPERATION_DURATION_SECONDS = "kairo_operation_duration_seconds";
    public static final String RUNTIME_RULE_TARGETS = "kairo_runtime_rule_targets";
    public static final String RECONCILE_TOTAL = "kairo_reconcile_total";
    public static final String ROLLBACK_TOTAL = "kairo_rollback_total";
    public static final String TTL_CLEANUP_TOTAL = "kairo_ttl_cleanup_total";
    public static final String PLATFORM_BUILD_INFO = "kairo_platform_build_info";

    /** All ten frozen meter names. */
    public static final Set<String> METER_NAMES = Set.of(
            AGENT_ONLINE, AGENT_COMMAND_BACKLOG, AGENT_COMMAND_TOTAL, OPERATION_TOTAL,
            OPERATION_DURATION_SECONDS, RUNTIME_RULE_TARGETS, RECONCILE_TOTAL, ROLLBACK_TOTAL,
            TTL_CLEANUP_TOTAL, PLATFORM_BUILD_INFO);

    // ----- Tag keys (the only keys any kairo_* meter may carry) -----

    public static final String TAG_STATUS = "status";
    public static final String TAG_COMMAND_TYPE = "command_type";
    public static final String TAG_RESULT = "result";
    public static final String TAG_OPERATION_TYPE = "operation_type";
    public static final String TAG_STATE = "state";
    public static final String TAG_VERSION = "version";
    public static final String TAG_COMMIT = "commit";

    /** The exact allowed tag-key set per meter (no more, no less). */
    public static final Set<String> TAGS_AGENT_ONLINE = Set.of(TAG_STATUS);
    public static final Set<String> TAGS_AGENT_COMMAND_BACKLOG = Set.of(TAG_STATUS, TAG_COMMAND_TYPE);
    public static final Set<String> TAGS_AGENT_COMMAND_TOTAL = Set.of(TAG_COMMAND_TYPE, TAG_RESULT);
    public static final Set<String> TAGS_OPERATION_TOTAL = Set.of(TAG_OPERATION_TYPE, TAG_RESULT);
    public static final Set<String> TAGS_OPERATION_DURATION = Set.of(TAG_OPERATION_TYPE, TAG_RESULT);
    public static final Set<String> TAGS_RUNTIME_RULE_TARGETS = Set.of(TAG_STATE);
    public static final Set<String> TAGS_RECONCILE_TOTAL = Set.of(TAG_RESULT);
    public static final Set<String> TAGS_ROLLBACK_TOTAL = Set.of(TAG_RESULT);
    public static final Set<String> TAGS_TTL_CLEANUP_TOTAL = Set.of(TAG_RESULT);
    public static final Set<String> TAGS_PLATFORM_BUILD_INFO = Set.of(TAG_VERSION, TAG_COMMIT);

    // ----- Bounded value vocabularies -----

    /** Stable fallback bucket for any value outside the relevant allowlist (never null). */
    public static final String OTHER = "OTHER";

    /** {@code agent_instance.status} values written by the platform lifecycle. */
    public static final List<String> AGENT_STATUSES =
            List.of("ACTIVE", "ONLINE", "STOPPING", "DISABLED", "OFFLINE", OTHER);

    /** {@code agent_command.status} values written by the dispatch/ack/expire state machine. */
    public static final List<String> COMMAND_STATUSES =
            List.of("PENDING", "DISPATCHED", "ACKED", "FAILED", OTHER);

    /** The frozen V1 dispatchable command-type set (see {@link KairoCommandCapabilities#V1}). */
    public static final List<String> COMMAND_TYPES = List.copyOf(KairoCommandCapabilities.V1);

    /** Bounded command terminal outcomes. TIMEOUT = retries exhausted; the agent never reports it. */
    public static final List<String> COMMAND_RESULTS = List.of("SUCCESS", "FAILURE", "TIMEOUT", OTHER);

    /** Bounded operation terminal outcomes (see {@link OperationType}). */
    public static final List<String> OPERATION_TYPES = operationTypes();

    /** Bounded operation results. REVERTED is a post-terminal lifecycle event, not counted here. */
    public static final List<String> OPERATION_RESULTS =
            List.of("SUCCESS", "FAILURE", "CANCELLED", "TIMEOUT", OTHER);

    /** {@code rule_target.drift_status} values (runtime state of a defined target). {@code null} -> OTHER. */
    public static final List<String> RULE_TARGET_STATES =
            List.of("FRESH", "DRIFTED", "UNRESOLVED", OTHER);

    /** Bounded reconciliation cycle outcomes (per-agent). */
    public static final List<String> RECONCILE_RESULTS = List.of("SUCCESS", "FAILURE", OTHER);

    /** Bounded rollback (precise unload) terminal outcomes. */
    public static final List<String> ROLLBACK_RESULTS = List.of("SUCCESS", "FAILURE", OTHER);

    /** Bounded TTL-cleanup cycle outcomes. */
    public static final List<String> TTL_RESULTS = List.of("SUCCESS", "FAILURE", OTHER);

    private static List<String> operationTypes() {
        OperationType[] values = OperationType.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name();
        }
        return List.of(names);
    }

    /**
     * Map a raw value through a finite allowlist. A {@code null} or unrecognised value collapses to
     * {@link #OTHER} so persisted/user-controlled strings can never create an unbounded tag value.
     */
    public static String normalize(String value, Collection<String> allowlist) {
        if (value != null && allowlist.contains(value)) {
            return value;
        }
        return OTHER;
    }
}
