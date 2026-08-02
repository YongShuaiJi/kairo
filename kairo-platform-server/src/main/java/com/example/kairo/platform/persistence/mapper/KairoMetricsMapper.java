package com.example.kairo.platform.persistence.mapper;

import java.util.List;
import java.util.Map;

/**
 * V1.7 M4-B &sect;11.2: bounded aggregate queries that back the state gauges
 * ({@code kairo_agent_online}, {@code kairo_agent_command_backlog}, {@code kairo_runtime_rule_targets}).
 *
 * <p>Each query normalises persisted values to its finite metric vocabulary <em>before</em> grouping,
 * returning at most one row per bounded tag value even if a future version has written arbitrary new
 * states. A refresh therefore never materialises per-resource or per-raw-value rows and never touches
 * payload/result/error text. Queries run on the controlled gauge-refresh cadence (never on each
 * Prometheus scrape); the {@code KairoMetricsStateProvider} caches the result between refreshes.
 */
public interface KairoMetricsMapper {

    /** {@code agent_instance} counts grouped by {@code status}. */
    List<Map<String, Object>> countAgentsByStatus();

    /** {@code agent_command} counts grouped by {@code status, command_type} (the in-flight backlog). */
    List<Map<String, Object>> countCommandsByStatusAndType();

    /** {@code rule_target} counts grouped by {@code drift_status} (a {@code null} group is possible). */
    List<Map<String, Object>> countRuleTargetsByDriftStatus();
}
