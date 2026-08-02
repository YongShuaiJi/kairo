package com.example.kairo.platform.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * V1.7 M4-B &sect;11.2: the single façade through which platform lifecycle points record the counter and
 * timer meters. Call sites pass raw command/operation/result strings; this recorder normalises every
 * value through {@link KairoMetricsCatalog} and attaches exactly the allowed tag-key set, so no call site
 * can attach an arbitrary or high-cardinality tag (ruleId, agentId, className, traceId, username, ...).
 *
 * <p>Counters and timers are looked up (created-or-get) by name + normalised tags, so Micrometer de-dups
 * series. The {@link #NO_OP} sentinel lets services that are constructed directly in unit tests (not via
 * Spring) skip recording without null checks; in a running context Spring injects the real bean.
 */
@Component
public class KairoMetricsRecorder {

    /** Sentinel used as the field default before Spring injects the real recorder; records nothing. */
    public static final KairoMetricsRecorder NO_OP = new KairoMetricsRecorder(null);

    private final MeterRegistry registry;

    @Autowired
    public KairoMetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Record one command reaching a terminal state (ack success/failure, or a restart-recovered transient
     * failure). {@code result} is SUCCESS or FAILURE.
     */
    public void recordCommandOutcome(String commandType, String result) {
        if (registry == null) {
            return;
        }
        String type = KairoMetricsCatalog.normalize(commandType, KairoMetricsCatalog.COMMAND_TYPES);
        String res = KairoMetricsCatalog.normalize(result, KairoMetricsCatalog.COMMAND_RESULTS);
        registry.counter(KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                Tags.of(KairoMetricsCatalog.TAG_COMMAND_TYPE, type, KairoMetricsCatalog.TAG_RESULT, res))
                .increment();
    }

    /**
     * Record {@code count} commands that exhausted their retries and were failed by the poll-triggered
     * sweep (the timeout path). The exhausted set is bulk-failed across command types, so the
     * command_type collapses to {@link KairoMetricsCatalog#OTHER} and the result is TIMEOUT.
     */
    public void recordCommandsExhausted(long count) {
        if (registry == null || count <= 0) {
            return;
        }
        registry.counter(KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                Tags.of(KairoMetricsCatalog.TAG_COMMAND_TYPE, KairoMetricsCatalog.OTHER,
                        KairoMetricsCatalog.TAG_RESULT, "TIMEOUT"))
                .increment(count);
    }

    /**
     * Record one operation reaching a terminal state and its wall-clock duration since creation.
     * {@code result} is SUCCESS, FAILURE or CANCELLED; {@code durationNanos} is the created-at -> terminal
     * duration. Both the counter ({@code kairo_operation_total}) and the timer
     * ({@code kairo_operation_duration_seconds}) are tagged with operation_type + result.
     */
    public void recordOperationOutcome(String operationType, String result, long durationNanos) {
        if (registry == null) {
            return;
        }
        String type = KairoMetricsCatalog.normalize(operationType, KairoMetricsCatalog.OPERATION_TYPES);
        String res = KairoMetricsCatalog.normalize(result, KairoMetricsCatalog.OPERATION_RESULTS);
        Tags tags = Tags.of(KairoMetricsCatalog.TAG_OPERATION_TYPE, type, KairoMetricsCatalog.TAG_RESULT, res);
        registry.counter(KairoMetricsCatalog.OPERATION_TOTAL, tags).increment();
        Timer timer = registry.timer(KairoMetricsCatalog.OPERATION_DURATION_SECONDS, tags);
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /** Record one reconciliation cycle outcome (per-agent). {@code result} is SUCCESS or FAILURE. */
    public void recordReconcile(String result) {
        if (registry == null) {
            return;
        }
        String res = KairoMetricsCatalog.normalize(result, KairoMetricsCatalog.RECONCILE_RESULTS);
        registry.counter(KairoMetricsCatalog.RECONCILE_TOTAL,
                Tags.of(KairoMetricsCatalog.TAG_RESULT, res)).increment();
    }

    /** Record one precise-unload rollback terminal outcome. {@code result} is SUCCESS or FAILURE. */
    public void recordRollback(String result) {
        if (registry == null) {
            return;
        }
        String res = KairoMetricsCatalog.normalize(result, KairoMetricsCatalog.ROLLBACK_RESULTS);
        registry.counter(KairoMetricsCatalog.ROLLBACK_TOTAL,
                Tags.of(KairoMetricsCatalog.TAG_RESULT, res)).increment();
    }

    /** Record one TTL-cleanup cycle outcome. {@code result} is SUCCESS or FAILURE. */
    public void recordTtlCleanup(String result) {
        if (registry == null) {
            return;
        }
        String res = KairoMetricsCatalog.normalize(result, KairoMetricsCatalog.TTL_RESULTS);
        registry.counter(KairoMetricsCatalog.TTL_CLEANUP_TOTAL,
                Tags.of(KairoMetricsCatalog.TAG_RESULT, res)).increment();
    }
}
