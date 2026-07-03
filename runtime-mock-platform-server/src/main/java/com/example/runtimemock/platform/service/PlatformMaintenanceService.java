package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.persistence.mapper.RuleVersionLifecycleMapper;
import com.example.runtimemock.platform.rollout.RolloutExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;
    private final RuleVersionLifecycleMapper ruleVersionLifecycleMapper;
    private final ObjectProvider<RolloutExecutor> rolloutExecutor;
    private final Clock clock;

    @Value("${runtime-mock.platform.runtime-cleanup.retention-ms:1800000}")
    private long offlineRuntimeRetentionMs;

    @Autowired
    public PlatformMaintenanceService(RbacService rbacService,
                                      JdbcTemplate jdbcTemplate,
                                      RuleVersionLifecycleMapper ruleVersionLifecycleMapper,
                                      ObjectProvider<RolloutExecutor> rolloutExecutor) {
        this(rbacService, jdbcTemplate, ruleVersionLifecycleMapper, rolloutExecutor, Clock.systemUTC());
    }

    PlatformMaintenanceService(RbacService rbacService,
                                      JdbcTemplate jdbcTemplate,
                                      RuleVersionLifecycleMapper ruleVersionLifecycleMapper,
                                      ObjectProvider<RolloutExecutor> rolloutExecutor,
                                      Clock clock) {
        this.rbacService = rbacService;
        this.jdbcTemplate = jdbcTemplate;
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
            initialDelayString = "${runtime-mock.platform.runtime-lease.initial-delay-ms:5000}",
            fixedDelayString = "${runtime-mock.platform.runtime-lease.fixed-delay-ms:5000}")
    public void expireRuntimeLeasesScheduled() {
        expireRuntimeLeases();
    }

    @Scheduled(
            initialDelayString = "${runtime-mock.platform.runtime-cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${runtime-mock.platform.runtime-cleanup.fixed-delay-ms:60000}")
    public void cleanupOfflineRuntimeScheduled() {
        cleanupOfflineRuntime();
        cleanupExpiredDisabledRules();
    }

    public Map<String, Object> expireRuntimeLeases() {
        Instant now = clock.instant();
        Timestamp current = Timestamp.from(now);
        int agentsOffline = jdbcTemplate.update("""
                update agent_instance
                   set status = 'OFFLINE',
                       updated_at = ?
                 where status in ('ACTIVE', 'ONLINE', 'STOPPING')
                   and lease_expires_at is not null
                   and lease_expires_at <= ?
                """, current, current);
        int executorsOffline = jdbcTemplate.update("""
                update attach_executor
                   set status = 'OFFLINE',
                       updated_at = ?
                 where status in ('ACTIVE', 'ONLINE')
                   and lease_expires_at is not null
                   and lease_expires_at <= ?
                """, current, current);
        int targetsOffline = jdbcTemplate.update("""
                update attach_executor_target t
                   set status = 'OFFLINE',
                       updated_at = ?
                 where status in ('ACTIVE', 'ONLINE')
                   and exists (
                       select 1
                         from attach_executor e
                        where e.id = t.executor_id
                          and e.status = 'OFFLINE'
                   )
                """, current);
        int instancesOffline = jdbcTemplate.update("""
                update instance
                   set status = 'OFFLINE',
                       updated_at = ?
                 where status in ('ACTIVE', 'ONLINE')
                   and lease_expires_at is not null
                   and lease_expires_at <= ?
                """, current, current);
        int operationPlansAutoUnloaded = markPlansUnloadedWhenAgentsGone(current);
        return Map.of(
                "agentsOffline", agentsOffline,
                "executorsOffline", executorsOffline,
                "targetsOffline", targetsOffline,
                "instancesOffline", instancesOffline,
                "operationPlansAutoUnloaded", operationPlansAutoUnloaded
        );
    }

    public Map<String, Object> cleanupOfflineRuntime() {
        Instant cutoff = clock.instant().minusMillis(Math.max(0, offlineRuntimeRetentionMs));
        Timestamp cutoffTimestamp = Timestamp.from(cutoff);
        int operationPlansAbandoned = markPlansAbandonedWhenInstancesGone(cutoffTimestamp);
        int executionsAbandoned = markExecutionsAbandonedWhenInstancesGone(cutoffTimestamp);

        int attachCommandsDeleted = jdbcTemplate.update("""
                delete from attach_executor_command
                 where status in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')
                   and coalesce(finished_at, lease_expires_at, updated_at, created_at) <= ?
                """, cutoffTimestamp);
        int agentCommandsDeleted = jdbcTemplate.update("""
                delete from agent_command
                 where status in ('ACKED', 'FAILED', 'CANCELLED', 'EXPIRED')
                   and coalesce(completed_at, lease_expires_at, updated_at, created_at) <= ?
                """, cutoffTimestamp);

        int agentHeartbeatsDeleted = jdbcTemplate.update("""
                delete from agent_heartbeat h
                 where exists (
                       select 1
                         from agent_instance a
                        where a.id = h.agent_id
                          and a.status = 'OFFLINE'
                          and coalesce(a.lease_expires_at, a.last_heartbeat_at, a.updated_at, a.created_at) <= ?
                   )
                """, cutoffTimestamp);
        int agentCapabilitiesDeleted = jdbcTemplate.update("""
                delete from agent_capability c
                 where exists (
                       select 1
                         from agent_instance a
                        where a.id = c.agent_id
                          and a.status = 'OFFLINE'
                          and coalesce(a.lease_expires_at, a.last_heartbeat_at, a.updated_at, a.created_at) <= ?
                   )
                """, cutoffTimestamp);
        int agentRegistrationsDeleted = jdbcTemplate.update("""
                delete from agent_registration r
                 where exists (
                       select 1
                         from agent_instance a
                        where a.id = r.agent_id
                          and a.status = 'OFFLINE'
                          and coalesce(a.lease_expires_at, a.last_heartbeat_at, a.updated_at, a.created_at) <= ?
                   )
                """, cutoffTimestamp);
        int degradedClassesDeleted = jdbcTemplate.update("""
                delete from degraded_class d
                 where exists (
                       select 1
                         from agent_instance a
                        where a.id = d.agent_id
                          and a.status = 'OFFLINE'
                          and coalesce(a.lease_expires_at, a.last_heartbeat_at, a.updated_at, a.created_at) <= ?
                   )
                """, cutoffTimestamp);
        int agentsDeleted = jdbcTemplate.update("""
                delete from agent_instance a
                 where a.status = 'OFFLINE'
                   and coalesce(a.lease_expires_at, a.last_heartbeat_at, a.updated_at, a.created_at) <= ?
                   and not exists (select 1 from agent_command c where c.agent_id = a.id)
                """, cutoffTimestamp);

        int sidecarsDeleted = jdbcTemplate.update("""
                delete from sidecar_instance s
                 where coalesce(s.last_heartbeat_at, s.updated_at, s.created_at) <= ?
                   and (
                       s.status = 'OFFLINE'
                       or exists (
                           select 1
                             from instance i
                            where i.id = s.instance_id
                              and i.status = 'OFFLINE'
                              and coalesce(i.lease_expires_at, i.last_seen_at, i.updated_at, i.created_at) <= ?
                       )
                   )
                   and not exists (select 1 from agent_instance a where a.sidecar_id = s.id)
                """, cutoffTimestamp, cutoffTimestamp);
        int attachTargetsDeleted = jdbcTemplate.update("""
                delete from attach_executor_target t
                 where t.status = 'OFFLINE'
                   and coalesce(t.last_seen_at, t.updated_at, t.created_at) <= ?
                """, cutoffTimestamp);
        int attachExecutorsDeleted = jdbcTemplate.update("""
                delete from attach_executor e
                 where e.status = 'OFFLINE'
                   and coalesce(e.lease_expires_at, e.last_heartbeat_at, e.updated_at, e.created_at) <= ?
                   and not exists (select 1 from attach_executor_target t where t.executor_id = e.id)
                   and not exists (select 1 from attach_executor_command c where c.executor_id = e.id)
                   and not exists (select 1 from sidecar_instance s where s.executor_id = e.id)
                """, cutoffTimestamp);

        int ruleRuntimeStatusesDeleted = jdbcTemplate.update("""
                delete from rule_runtime_status r
                 where exists (
                       select 1
                         from instance i
                        where i.id = r.instance_id
                          and i.status = 'OFFLINE'
                          and coalesce(i.lease_expires_at, i.last_seen_at, i.updated_at, i.created_at) <= ?
                   )
                """, cutoffTimestamp);
        int ruleInstanceBindingsDeleted = jdbcTemplate.update("""
                delete from rule_instance_binding b
                 where exists (
                       select 1
                         from instance i
                        where i.id = b.instance_id
                          and i.status = 'OFFLINE'
                          and coalesce(i.lease_expires_at, i.last_seen_at, i.updated_at, i.created_at) <= ?
                   )
                """, cutoffTimestamp);

        int instanceLabelsDeleted = jdbcTemplate.update("""
                delete from instance_label l
                 where exists (
                       select 1
                         from instance i
                        where i.id = l.instance_id
                          and i.status = 'OFFLINE'
                          and coalesce(i.lease_expires_at, i.last_seen_at, i.updated_at, i.created_at) <= ?
                   )
                """, cutoffTimestamp);
        int assetClaimsDeleted = jdbcTemplate.update("""
                delete from asset_claim ac
                 where exists (
                       select 1
                        from instance i
                        where i.id = ac.instance_id
                          and i.status = 'OFFLINE'
                          and coalesce(i.lease_expires_at, i.last_seen_at, i.updated_at, i.created_at) <= ?
                   )
                """, cutoffTimestamp);
        int instancesDeleted = jdbcTemplate.update("""
                update instance i
                   set status = 'ARCHIVED',
                       updated_at = current_timestamp
                 where i.status = 'OFFLINE'
                   and coalesce(i.lease_expires_at, i.last_seen_at, i.updated_at, i.created_at) <= ?
                """, cutoffTimestamp);

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
        int plans = jdbcTemplate.update("""
                update operation_plan op
                   set status = 'UNLOADED',
                       version = version + 1,
                       terminal_source = 'AGENT_GONE',
                       terminal_reason = '成功执行实例已没有可达 Agent，规则运行时能力自动失效',
                       updated_by = 'runtime-maintenance',
                       updated_at = ?
                 where op.status in ('SUCCEEDED', 'RUNNING', 'FAILED')
                   and op.resource_type = 'rule'
                   and exists (
                       select 1
                         from rollout_instance_execution rie
                        where rie.operation_plan_id = op.id
                          and rie.status = 'SUCCEEDED'
                   )
                   and not exists (
                       select 1
                         from rollout_instance_execution rie
                         join agent_instance a on a.instance_id = rie.instance_id
                        where rie.operation_plan_id = op.id
                          and rie.status = 'SUCCEEDED'
                          and a.status in ('ACTIVE', 'ONLINE')
                          and (a.lease_expires_at is null or a.lease_expires_at > ?)
                   )
                """, now, now);
        if (plans > 0) {
            jdbcTemplate.update("""
                    update rule_runtime_status r
                       set status = 'REMOVED',
                           last_error = null,
                           updated_at = ?
                     where exists (
                         select 1
                           from operation_plan op
                           join rollout_instance_execution rie on rie.operation_plan_id = op.id
                          where op.status = 'UNLOADED'
                            and op.terminal_source = 'AGENT_GONE'
                            and rie.status = 'SUCCEEDED'
                            and rie.instance_id = r.instance_id
                            and op.resource_id = r.rule_id
                            and op.resource_version = r.rule_version
                     )
                    """, now);
        }
        return plans;
    }

    private int markPlansAbandonedWhenInstancesGone(Timestamp cutoffTimestamp) {
        return jdbcTemplate.update("""
                update operation_plan op
                   set status = 'ABANDONED',
                       version = version + 1,
                       terminal_source = 'INSTANCE_GONE',
                       terminal_reason = '目标实例已被运行时清理，计划仅保留历史痕迹',
                       updated_by = 'runtime-maintenance',
                       updated_at = current_timestamp
                 where op.status <> 'ABANDONED'
                   and exists (
                       select 1
                         from rollout_instance_execution rie
                        where rie.operation_plan_id = op.id
                   )
                   and not exists (
                       select 1
                         from rollout_instance_execution rie
                         join instance i on i.id = rie.instance_id
                        where rie.operation_plan_id = op.id
                          and not (
                              i.status in ('OFFLINE', 'ARCHIVED')
                              and coalesce(i.lease_expires_at, i.last_seen_at, i.updated_at, i.created_at) <= ?
                          )
                   )
                """, cutoffTimestamp);
    }

    private int markExecutionsAbandonedWhenInstancesGone(Timestamp cutoffTimestamp) {
        return jdbcTemplate.update("""
                update rollout_instance_execution rie
                   set status = 'ABANDONED',
                       error_message = '目标实例已被运行时清理',
                       finished_at = coalesce(finished_at, current_timestamp),
                       updated_at = current_timestamp
                 where status <> 'ABANDONED'
                   and exists (
                       select 1
                         from instance i
                        where i.id = rie.instance_id
                          and i.status in ('OFFLINE', 'ARCHIVED')
                          and coalesce(i.lease_expires_at, i.last_seen_at, i.updated_at, i.created_at) <= ?
                   )
                """, cutoffTimestamp);
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
