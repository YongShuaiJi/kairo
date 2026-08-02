package com.example.kairo.platform.metrics;

import com.example.kairo.platform.health.KairoBuildIdentity;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

/**
 * V1.7 M4-B &sect;11.2: registers the four gauge meters against the actuator-managed {@link MeterRegistry}
 * (no second metrics framework, no custom endpoint, no per-resource meter objects).
 *
 * <p>The three state gauges ({@code kairo_agent_online}, {@code kairo_agent_command_backlog},
 * {@code kairo_runtime_rule_targets}) are pre-registered for the full bounded value cross-product of their
 * allowlists, so a Prometheus scrape always sees a fixed, bounded series set and an unexpected DB value
 * (which the refresh collapsed into {@code OTHER}) can never grow the cardinality. Each gauge reads the
 * cached count from {@link KairoMetricsStateProvider} (never the database).
 *
 * <p>{@code kairo_platform_build_info} is a constant-1 gauge tagged with the exact version/commit identity
 * from {@link KairoBuildIdentity} (the same source {@code /actuator/info} uses), so it cannot drift.
 */
@Component
public class KairoMetricsBinder implements MeterBinder {

    private final KairoMetricsStateProvider stateProvider;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final ObjectProvider<GitProperties> gitPropertiesProvider;

    public KairoMetricsBinder(KairoMetricsStateProvider stateProvider,
                             ObjectProvider<BuildProperties> buildPropertiesProvider,
                             ObjectProvider<GitProperties> gitPropertiesProvider) {
        this.stateProvider = stateProvider;
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.gitPropertiesProvider = gitPropertiesProvider;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registerBuildInfo(registry);
        registerAgentOnline(registry);
        registerCommandBacklog(registry);
        registerRuleTargets(registry);
    }

    private void registerBuildInfo(MeterRegistry registry) {
        BuildProperties build = buildPropertiesProvider.getIfAvailable();
        GitProperties git = gitPropertiesProvider.getIfAvailable();
        Gauge.builder(KairoMetricsCatalog.PLATFORM_BUILD_INFO, this, self -> 1.0)
                .tag(KairoMetricsCatalog.TAG_VERSION, KairoBuildIdentity.version(build))
                .tag(KairoMetricsCatalog.TAG_COMMIT, KairoBuildIdentity.commit(git))
                .register(registry);
    }

    private void registerAgentOnline(MeterRegistry registry) {
        for (String status : KairoMetricsCatalog.AGENT_STATUSES) {
            Gauge.builder(KairoMetricsCatalog.AGENT_ONLINE, stateProvider, sp -> sp.agentCount(status))
                    .tag(KairoMetricsCatalog.TAG_STATUS, status)
                    .register(registry);
        }
    }

    private void registerCommandBacklog(MeterRegistry registry) {
        // The command_type dimension is the V1 vocabulary plus OTHER (the normalisation fallback),
        // so a DB command_type the refresh collapsed to OTHER still has a gauge to read.
        java.util.List<String> commandTypes = new java.util.ArrayList<>(KairoMetricsCatalog.COMMAND_TYPES);
        commandTypes.add(KairoMetricsCatalog.OTHER);
        for (String status : KairoMetricsCatalog.COMMAND_STATUSES) {
            for (String commandType : commandTypes) {
                Gauge.builder(KairoMetricsCatalog.AGENT_COMMAND_BACKLOG,
                                stateProvider, sp -> sp.commandBacklogCount(status, commandType))
                        .tag(KairoMetricsCatalog.TAG_STATUS, status)
                        .tag(KairoMetricsCatalog.TAG_COMMAND_TYPE, commandType)
                        .register(registry);
            }
        }
    }

    private void registerRuleTargets(MeterRegistry registry) {
        for (String state : KairoMetricsCatalog.RULE_TARGET_STATES) {
            Gauge.builder(KairoMetricsCatalog.RUNTIME_RULE_TARGETS, stateProvider, sp -> sp.ruleTargetCount(state))
                    .tag(KairoMetricsCatalog.TAG_STATE, state)
                    .register(registry);
        }
    }
}
