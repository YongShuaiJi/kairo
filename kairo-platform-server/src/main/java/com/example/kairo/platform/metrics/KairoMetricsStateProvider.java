package com.example.kairo.platform.metrics;

import com.example.kairo.platform.persistence.mapper.KairoMetricsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V1.7 M4-B &sect;11.2: bounded-state cache for the three gauges
 * ({@code kairo_agent_online}, {@code kairo_agent_command_backlog}, {@code kairo_runtime_rule_targets}).
 *
 * <p>Gauges must reflect authoritative current state without scanning the database on every scrape, so
 * a refresh runs the three bounded {@code GROUP BY} aggregates in {@link KairoMetricsMapper} on a
 * controlled cadence and caches the normalized counts in immutable maps. Gauge value suppliers read the
 * cache (never the database), so a Prometheus scrape is free. Stale-value semantics are explicit: a
 * gauge value is the last refresh's count, at most one refresh interval old, and {@link #lastRefreshAt()}
 * exposes that instant for tests.
 *
 * <p>Refresh normalises every raw status / command_type / drift_status through {@link KairoMetricsCatalog}
 * so an unexpected (or future) value collapses into the {@code OTHER} bucket and never creates a new tag
 * value. A {@code null} drift_status also collapses to {@code OTHER}. {@code null} keys are tolerated by
 * lower-casing row keys, so the driver's returned alias case does not matter.
 */
@Component
public class KairoMetricsStateProvider {

    private static final Logger log = LoggerFactory.getLogger(KairoMetricsStateProvider.class);

    private final KairoMetricsMapper mapper;
    private final Clock clock;

    private volatile Map<String, Long> agentCounts = Map.of();
    private volatile Map<String, Long> commandBacklog = Map.of();
    private volatile Map<String, Long> ruleTargetCounts = Map.of();
    private volatile Instant lastRefreshAt = null;

    @Autowired
    public KairoMetricsStateProvider(KairoMetricsMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    KairoMetricsStateProvider(KairoMetricsMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${kairo.platform.metrics.gauge-refresh.initial-delay-ms:5000}",
            fixedDelayString = "${kairo.platform.metrics.gauge-refresh.fixed-delay-ms:15000}")
    public void scheduledRefresh() {
        refresh();
    }

    /**
     * Re-run the three bounded aggregates and replace the cache. Synchronised so a manual refresh and a
     * scheduled refresh never overlap; readers are lock-free (volatile immutable maps).
     */
    public synchronized void refresh() {
        try {
            Map<String, Long> agent = new HashMap<>();
            for (Map<String, Object> row : mapper.countAgentsByStatus()) {
                Map<String, Object> lower = lowerKeys(row);
                String status = KairoMetricsCatalog.normalize(str(lower.get("status")),
                        KairoMetricsCatalog.AGENT_STATUSES);
                agent.merge(status, asLong(lower.get("total")), Long::sum);
            }

            Map<String, Long> backlog = new HashMap<>();
            for (Map<String, Object> row : mapper.countCommandsByStatusAndType()) {
                Map<String, Object> lower = lowerKeys(row);
                String status = KairoMetricsCatalog.normalize(str(lower.get("status")),
                        KairoMetricsCatalog.COMMAND_STATUSES);
                String type = KairoMetricsCatalog.normalize(str(lower.get("command_type")),
                        KairoMetricsCatalog.COMMAND_TYPES);
                backlog.merge(status + "|" + type, asLong(lower.get("total")), Long::sum);
            }

            Map<String, Long> targets = new HashMap<>();
            for (Map<String, Object> row : mapper.countRuleTargetsByDriftStatus()) {
                Map<String, Object> lower = lowerKeys(row);
                // null drift_status collapses to OTHER via normalize (null is not in the allowlist).
                String state = KairoMetricsCatalog.normalize(str(lower.get("drift_status")),
                        KairoMetricsCatalog.RULE_TARGET_STATES);
                targets.merge(state, asLong(lower.get("total")), Long::sum);
            }

            this.agentCounts = Map.copyOf(agent);
            this.commandBacklog = Map.copyOf(backlog);
            this.ruleTargetCounts = Map.copyOf(targets);
            this.lastRefreshAt = clock.instant();
        } catch (RuntimeException e) {
            // A refresh failure (e.g. a transient DB blip) must not bring down scrapes: gauges keep
            // serving the last good cache, and the next scheduled refresh retries. Logged, not thrown.
            log.warn("Kairo metrics gauge refresh failed (gauges retain last cache): {}", e.getMessage());
        }
    }

    /** Cached count of agents in {@code status} (already normalised by the last refresh). */
    public long agentCount(String status) {
        return agentCounts.getOrDefault(status, 0L);
    }

    /** Cached count of commands in {@code status} for {@code commandType} (already normalised). */
    public long commandBacklogCount(String status, String commandType) {
        return commandBacklog.getOrDefault(status + "|" + commandType, 0L);
    }

    /** Cached count of rule targets in {@code state} (already normalised). */
    public long ruleTargetCount(String state) {
        return ruleTargetCounts.getOrDefault(state, 0L);
    }

    /** When the cache was last refreshed, or {@code null} if never refreshed. */
    public Instant lastRefreshAt() {
        return lastRefreshAt;
    }

    private static Map<String, Object> lowerKeys(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row != null) {
            row.forEach((k, v) -> out.put(k == null ? null : k.toLowerCase(), v));
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }
}
