package com.example.kairo.platform.service;

import com.example.kairo.platform.persistence.mapper.PlatformMaintenanceMapper;
import com.example.kairo.platform.persistence.mapper.RuleVersionLifecycleMapper;
import com.example.kairo.platform.rollout.RolloutExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PlatformMaintenanceService {

    private final RbacService rbacService;
    private final PlatformMaintenanceMapper maintenanceMapper;
    private final RuleVersionLifecycleMapper ruleVersionLifecycleMapper;
    private final ObjectProvider<RolloutExecutor> rolloutExecutor;
    private final Clock clock;

    @Value("${kairo.platform.runtime-cleanup.retention-ms:1800000}")
    private long offlineRuntimeRetentionMs;

    @Autowired
    public PlatformMaintenanceService(RbacService rbacService,
                                      PlatformMaintenanceMapper maintenanceMapper,
                                      RuleVersionLifecycleMapper ruleVersionLifecycleMapper,
                                      ObjectProvider<RolloutExecutor> rolloutExecutor) {
        this(rbacService, maintenanceMapper, ruleVersionLifecycleMapper, rolloutExecutor, Clock.systemUTC());
    }

    PlatformMaintenanceService(RbacService rbacService,
                                      PlatformMaintenanceMapper maintenanceMapper,
                                      RuleVersionLifecycleMapper ruleVersionLifecycleMapper,
                                      ObjectProvider<RolloutExecutor> rolloutExecutor,
                                      Clock clock) {
        this.rbacService = rbacService;
        this.maintenanceMapper = maintenanceMapper;
        this.ruleVersionLifecycleMapper = ruleVersionLifecycleMapper;
        this.rolloutExecutor = rolloutExecutor;
        this.clock = clock;
    }

    public Map<String, Object> runOnce(RequestContext context) {
        rbacService.require(context, "ADMIN");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runtimeLeases", expireRuntimeLeases());
        result.put("runtimeCleanup", cleanupOfflineRuntime());
        result.put("disabledRuleCleanup", cleanupExpiredDisabledRules());
        RolloutExecutor rollout = rolloutExecutor.getIfAvailable();
        result.put("rollout", rollout == null ? Map.of("status", "DISABLED") : rollout.runOnce(context));
        return result;
    }

    @Scheduled(
            initialDelayString = "${kairo.platform.runtime-lease.initial-delay-ms:5000}",
            fixedDelayString = "${kairo.platform.runtime-lease.fixed-delay-ms:5000}")
    public void expireRuntimeLeasesScheduled() {
        expireRuntimeLeases();
    }

    @Scheduled(
            initialDelayString = "${kairo.platform.runtime-cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${kairo.platform.runtime-cleanup.fixed-delay-ms:60000}")
    public void cleanupOfflineRuntimeScheduled() {
        cleanupOfflineRuntime();
        cleanupExpiredDisabledRules();
    }

    public Map<String, Object> expireRuntimeLeases() {
        Instant now = clock.instant();
        Timestamp current = Timestamp.from(now);
        int agentsOffline = maintenanceMapper.expireAgents(current);
        int executorsOffline = maintenanceMapper.expireExecutors(current);
        int targetsOffline = maintenanceMapper.expireExecutorTargets(current);
        int sidecarsOffline = maintenanceMapper.expireSidecars(current);
        int instancesOffline = maintenanceMapper.expireInstances(current);
        int operationPlansAutoUnloaded = markPlansUnloadedWhenAgentsGone(current);
        return Map.of(
                "agentsOffline", agentsOffline,
                "executorsOffline", executorsOffline,
                "targetsOffline", targetsOffline,
                "sidecarsOffline", sidecarsOffline,
                "instancesOffline", instancesOffline,
                "operationPlansAutoUnloaded", operationPlansAutoUnloaded
        );
    }

    public Map<String, Object> cleanupOfflineRuntime() {
        Instant cutoff = clock.instant().minusMillis(Math.max(0, offlineRuntimeRetentionMs));
        Timestamp cutoffTimestamp = Timestamp.from(cutoff);
        int operationPlansAbandoned = markPlansAbandonedWhenInstancesGone(cutoffTimestamp);
        int executionsAbandoned = markExecutionsAbandonedWhenInstancesGone(cutoffTimestamp);

        int attachCommandsDeleted = maintenanceMapper.deleteCompletedAttachCommands(cutoffTimestamp);
        int agentCommandsDeleted = maintenanceMapper.deleteCompletedAgentCommands(cutoffTimestamp);
        int agentHeartbeatsDeleted = maintenanceMapper.deleteOfflineAgentHeartbeats(cutoffTimestamp);
        int agentCapabilitiesDeleted = maintenanceMapper.deleteOfflineAgentCapabilities(cutoffTimestamp);
        int agentRegistrationsDeleted = maintenanceMapper.deleteOfflineAgentRegistrations(cutoffTimestamp);
        int degradedClassesDeleted = maintenanceMapper.deleteOfflineDegradedClasses(cutoffTimestamp);
        int agentsDeleted = maintenanceMapper.deleteOfflineAgents(cutoffTimestamp);
        int sidecarsDeleted = maintenanceMapper.deleteOfflineSidecars(cutoffTimestamp);
        int attachTargetsDeleted = maintenanceMapper.deleteOfflineAttachTargets(cutoffTimestamp);
        int attachExecutorsDeleted = maintenanceMapper.deleteOfflineAttachExecutors(cutoffTimestamp);
        int ruleRuntimeStatusesDeleted = maintenanceMapper.deleteOfflineRuleRuntimeStatuses(cutoffTimestamp);
        int ruleInstanceBindingsDeleted = maintenanceMapper.deleteOfflineRuleInstanceBindings(cutoffTimestamp);
        int instanceLabelsDeleted = maintenanceMapper.deleteOfflineInstanceLabels(cutoffTimestamp);
        int assetClaimsDeleted = maintenanceMapper.deleteOfflineAssetClaims(cutoffTimestamp);
        int instancesDeleted = maintenanceMapper.archiveOfflineInstances(cutoffTimestamp);

        return Map.ofEntries(
                Map.entry("cutoff", cutoff.toString()),
                Map.entry("operationPlansAbandoned", operationPlansAbandoned),
                Map.entry("executionsAbandoned", executionsAbandoned),
                Map.entry("attachCommandsDeleted", attachCommandsDeleted),
                Map.entry("agentCommandsDeleted", agentCommandsDeleted),
                Map.entry("agentHeartbeatsDeleted", agentHeartbeatsDeleted),
                Map.entry("agentCapabilitiesDeleted", agentCapabilitiesDeleted),
                Map.entry("agentRegistrationsDeleted", agentRegistrationsDeleted),
                Map.entry("degradedClassesDeleted", degradedClassesDeleted),
                Map.entry("agentsDeleted", agentsDeleted),
                Map.entry("sidecarsDeleted", sidecarsDeleted),
                Map.entry("attachTargetsDeleted", attachTargetsDeleted),
                Map.entry("attachExecutorsDeleted", attachExecutorsDeleted),
                Map.entry("ruleRuntimeStatusesDeleted", ruleRuntimeStatusesDeleted),
                Map.entry("ruleInstanceBindingsDeleted", ruleInstanceBindingsDeleted),
                Map.entry("instanceLabelsDeleted", instanceLabelsDeleted),
                Map.entry("assetClaimsDeleted", assetClaimsDeleted),
                Map.entry("instancesDeleted", instancesDeleted)
        );
    }

    private int markPlansUnloadedWhenAgentsGone(Timestamp now) {
        int plans = maintenanceMapper.markPlansUnloadedWhenAgentsGone(now);
        if (plans > 0) {
            maintenanceMapper.markRuntimeStatusesRemovedForAgentGonePlans(now);
        }
        return plans;
    }

    private int markPlansAbandonedWhenInstancesGone(Timestamp cutoffTimestamp) {
        return maintenanceMapper.markPlansAbandonedWhenInstancesGone(cutoffTimestamp);
    }

    private int markExecutionsAbandonedWhenInstancesGone(Timestamp cutoffTimestamp) {
        return maintenanceMapper.markExecutionsAbandonedWhenInstancesGone(cutoffTimestamp);
    }

    public Map<String, Object> cleanupExpiredDisabledRules() {
        Timestamp now = Timestamp.from(clock.instant());
        int capabilitiesDeleted = ruleVersionLifecycleMapper.deleteExpiredCapabilities(now);
        int targetsDeleted = ruleVersionLifecycleMapper.deleteExpiredTargets(now);
        int runtimeStatusesDeleted = ruleVersionLifecycleMapper.deleteExpiredRuntimeStatuses(now);
        int bindingsDeleted = ruleVersionLifecycleMapper.deleteExpiredBindings(now);
        int versionsDeleted = ruleVersionLifecycleMapper.deleteExpiredRuleVersions(now);
        ruleVersionLifecycleMapper.refreshRuleAggregatesWithVersions(now);
        int locksDeleted = ruleVersionLifecycleMapper.deleteLocksWithoutVersions();
        int rulesDeleted = ruleVersionLifecycleMapper.deleteRulesWithoutVersions();
        return Map.of(
                "rulesDeleted", rulesDeleted,
                "versionsDeleted", versionsDeleted,
                "targetsDeleted", targetsDeleted,
                "capabilitiesDeleted", capabilitiesDeleted,
                "runtimeStatusesDeleted", runtimeStatusesDeleted,
                "bindingsDeleted", bindingsDeleted,
                "locksDeleted", locksDeleted
        );
    }
}
