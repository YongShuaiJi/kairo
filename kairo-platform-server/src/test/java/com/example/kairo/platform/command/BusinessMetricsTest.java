package com.example.kairo.platform.command;

import com.example.kairo.api.operation.OperationType;
import com.example.kairo.platform.KairoPlatformApplication;
import com.example.kairo.platform.health.KairoBuildIdentity;
import com.example.kairo.platform.metrics.KairoMetricsCatalog;
import com.example.kairo.platform.metrics.KairoMetricsRecorder;
import com.example.kairo.platform.metrics.KairoMetricsStateProvider;
import com.example.kairo.platform.operation.OperationService;
import com.example.kairo.platform.persistence.mapper.KairoMetricsMapper;
import com.example.kairo.platform.script.ScriptSessionService;
import com.example.kairo.platform.service.RequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedSucceededOperation;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedExecution;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-B &sect;11.2: drives the real platform lifecycle points (agent status, command ack/exhaustion,
 * operation, reconciliation, rollback, TTL cleanup) and observes the corresponding counter/timer/gauge
 * changes, then proves the meter contract: ten exact names with exact types, only allowed tag keys,
 * bounded cardinality under high-cardinality inputs, {@code OTHER}-collapse normalisation, and a
 * build-info gauge pinned to 1 with the same identity as {@code /actuator/info}.
 *
 * <p>Lives in the {@code command} package so it can exercise the package-private rollback completion
 * {@link AgentCommandService#tryCompleteUnload} directly with seeded state (the real instrumentation
 * point), avoiding the far heavier full real-JVM unload flow.
 */
@SpringBootTest(classes = KairoPlatformApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m4b_metrics;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "kairo.platform.rollout.scheduler.enabled=false",
        "kairo.platform.reconciliation.scheduler.enabled=false",
        "kairo.platform.runtime-lease.initial-delay-ms=999999",
        "kairo.platform.runtime-lease.fixed-delay-ms=999999",
        "kairo.platform.runtime-cleanup.initial-delay-ms=999999",
        "kairo.platform.runtime-cleanup.fixed-delay-ms=999999",
        "kairo.platform.script.expiry.initial-delay-ms=999999",
        "kairo.platform.script.expiry.fixed-delay-ms=999999",
        "kairo.platform.automation.expiry.initial-delay-ms=999999",
        "kairo.platform.automation.expiry.fixed-delay-ms=999999",
        "kairo.platform.metrics.gauge-refresh.initial-delay-ms=999999",
        "kairo.platform.metrics.gauge-refresh.fixed-delay-ms=999999"
})
@ActiveProfiles("test")
class BusinessMetricsTest {

    private static final String TARGET_CLASS = "com.test.MetricsTarget";
    private static final String TARGET_LOADER = "loader-metrics";
    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "ruleId", "agentId", "instanceId", "className", "methodName", "traceId", "correlationId",
            "username", "userId", "appId", "applicationId", "environmentId", "error", "exception",
            "script", "class", "method", "commandId", "operationId");

    @Autowired MeterRegistry registry;
    @Autowired KairoMetricsRecorder recorder;
    @Autowired KairoMetricsStateProvider stateProvider;
    @Autowired KairoMetricsMapper metricsMapper;
    @Autowired OperationService operationService;
    @Autowired AgentCommandService commands;
    @Autowired AgentReconciliationService reconciliation;
    @Autowired ScriptSessionService scriptSessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectProvider<BuildProperties> buildPropertiesProvider;
    @Autowired ObjectProvider<GitProperties> gitPropertiesProvider;

    private RequestContext admin;

    @BeforeEach
    void seedBase() {
        jdbc.update("insert into project(id, organization_id, name, created_at) "
                + "select 'proj-default','org-default','Default Project',current_timestamp "
                + "where not exists (select 1 from project where id='proj-default')");
        jdbc.update("insert into application(id, project_id, name, created_at) "
                + "select 'app-default','proj-default','Default Application',current_timestamp "
                + "where not exists (select 1 from application where id='app-default')");
        jdbc.update("insert into environment(id, application_id, name, type, created_at) "
                + "select 'env-dev','app-default','dev','dev',current_timestamp "
                + "where not exists (select 1 from environment where id='env-dev')");
        admin = new RequestContext("system", "corr-metrics", "127.0.0.1", "system", "test");
    }

    // -------------------------------------------------------- contract: names, types, tag keys

    @Test
    void allTenMetersRegisteredWithExactType() {
        // Drive one of each counter/timer path so the lazily-created meters materialise; gauges and
        // build-info are pre-registered by KairoMetricsBinder.
        driveOperationSuccess();
        driveCommandAck("ACKED", "SUCCESS");
        driveReconcile();
        driveTtlCleanup();
        driveRollback("UNLOADED");

        assertThat(meterNames()).containsExactlyInAnyOrderElementsOf(KairoMetricsCatalog.METER_NAMES);
        assertThat(gaugeNames()).containsExactlyInAnyOrder(
                KairoMetricsCatalog.AGENT_ONLINE, KairoMetricsCatalog.AGENT_COMMAND_BACKLOG,
                KairoMetricsCatalog.RUNTIME_RULE_TARGETS, KairoMetricsCatalog.PLATFORM_BUILD_INFO);
        assertThat(counterNames()).containsExactlyInAnyOrder(
                KairoMetricsCatalog.AGENT_COMMAND_TOTAL, KairoMetricsCatalog.OPERATION_TOTAL,
                KairoMetricsCatalog.RECONCILE_TOTAL, KairoMetricsCatalog.ROLLBACK_TOTAL,
                KairoMetricsCatalog.TTL_CLEANUP_TOTAL);
        assertThat(timerNames()).containsExactly(KairoMetricsCatalog.OPERATION_DURATION_SECONDS);
    }

    @Test
    void everyMeterHasOnlyItsAllowedTagKeysAndNoForbiddenKey() {
        driveOperationSuccess();
        driveCommandAck("ACKED", "SUCCESS");
        driveCommandAck("FAILED", "FAILURE");
        recorder.recordCommandsExhausted(1);
        driveReconcile();
        driveTtlCleanup();
        driveRollback("UNLOADED");
        // Force a few operation/reconcile/rollback result series so their tag keys are observable.
        recorder.recordOperationOutcome(OperationType.AGENT_COMMAND.name(), "FAILURE", 1_000L);
        recorder.recordReconcile("FAILURE");
        recorder.recordRollback("FAILURE");
        stateProvider.refresh();

        for (Meter meter : kairoMeters()) {
            Set<String> keys = tagKeys(meter);
            assertThat(keys).as("tag keys for %s", meter.getId()).isEqualTo(allowedKeysFor(meter.getId().getName()));
            assertThat(keys).as("forbidden key in %s", meter.getId())
                    .doesNotContainAnyElementsOf(FORBIDDEN_TAG_KEYS);
        }
    }

    // -------------------------------------------------------- cardinality + normalisation

    @Test
    void highCardinalityInputsNeverProduceUnboundedSeries() {
        // Many distinct resource ids / class+method names / user values that must NEVER become tags.
        for (int i = 0; i < 40; i++) {
            String fakeId = "agent-host-" + i + ":17" + i;
            String fakeClass = "com.example.Svc" + i;
            String fakeMethod = "m" + i;
            String user = "user-" + i;
            // Pretend the platform saw these as command_type / operation_type / result / status / state.
            recorder.recordCommandOutcome(fakeId, fakeMethod);
            recorder.recordOperationOutcome(fakeClass, user, 1_000L);
            recorder.recordReconcile(fakeId);
            recorder.recordRollback(fakeMethod);
            recorder.recordTtlCleanup(fakeClass);
        }

        Set<String> allowedCommandTypes = new java.util.HashSet<>(KairoMetricsCatalog.COMMAND_TYPES);
        allowedCommandTypes.add(KairoMetricsCatalog.OTHER);
        Set<String> allowedOperationTypes = new java.util.HashSet<>(KairoMetricsCatalog.OPERATION_TYPES);
        allowedOperationTypes.add(KairoMetricsCatalog.OTHER);

        Set<String> commandTypeValues = tagValues(KairoMetricsCatalog.AGENT_COMMAND_TOTAL, KairoMetricsCatalog.TAG_COMMAND_TYPE);
        Set<String> operationTypeValues = tagValues(KairoMetricsCatalog.OPERATION_TOTAL, KairoMetricsCatalog.TAG_OPERATION_TYPE);
        Set<String> commandResults = tagValues(KairoMetricsCatalog.AGENT_COMMAND_TOTAL, KairoMetricsCatalog.TAG_RESULT);

        assertThat(commandTypeValues).isSubsetOf(allowedCommandTypes);
        assertThat(operationTypeValues).isSubsetOf(allowedOperationTypes);
        assertThat(commandResults).isSubsetOf(KairoMetricsCatalog.COMMAND_RESULTS);
        // None of the high-cardinality inputs leaked into any tag value.
        Set<String> allValues = kairoMeters().stream()
                .flatMap(m -> m.getId().getTags().stream()).map(Tag::getValue).collect(Collectors.toSet());
        for (int i = 0; i < 40; i++) {
            assertThat(allValues).doesNotContain("agent-host-" + i + ":17" + i);
            assertThat(allValues).doesNotContain("com.example.Svc" + i);
            assertThat(allValues).doesNotContain("m" + i);
            assertThat(allValues).doesNotContain("user-" + i);
        }
        // Bounded series count: each counter never exceeds its allowlist cross-product.
        assertThat(meters(KairoMetricsCatalog.AGENT_COMMAND_TOTAL)).hasSizeLessThanOrEqualTo(
                KairoMetricsCatalog.COMMAND_TYPES.size() * KairoMetricsCatalog.COMMAND_RESULTS.size());
        assertThat(meters(KairoMetricsCatalog.OPERATION_TOTAL)).hasSizeLessThanOrEqualTo(
                KairoMetricsCatalog.OPERATION_TYPES.size() * KairoMetricsCatalog.OPERATION_RESULTS.size());
        // Pre-registered gauges have a FIXED series count (the allowlist cross-product) regardless of
        // how many distinct resources exist: traffic changes values, never the series set.
        assertThat(meters(KairoMetricsCatalog.AGENT_ONLINE)).hasSize(KairoMetricsCatalog.AGENT_STATUSES.size());
        assertThat(meters(KairoMetricsCatalog.AGENT_COMMAND_BACKLOG))
                .hasSize(KairoMetricsCatalog.COMMAND_STATUSES.size() * (KairoMetricsCatalog.COMMAND_TYPES.size() + 1));
        assertThat(meters(KairoMetricsCatalog.RUNTIME_RULE_TARGETS)).hasSize(KairoMetricsCatalog.RULE_TARGET_STATES.size());
        assertThat(meters(KairoMetricsCatalog.PLATFORM_BUILD_INFO)).hasSize(1);
    }

    @Test
    void unknownValuesCollapseToOtherNotNewTags() {
        recorder.recordCommandOutcome("NOT_A_REAL_COMMAND_TYPE", "WEIRD_RESULT");
        recorder.recordOperationOutcome("NOT_A_REAL_OPERATION_TYPE", "WEIRD", 500L);
        recorder.recordReconcile("BIZARRE");
        recorder.recordRollback("BIZARRE");
        recorder.recordTtlCleanup("BIZARRE");

        Set<String> commandTypes = tagValues(KairoMetricsCatalog.AGENT_COMMAND_TOTAL, KairoMetricsCatalog.TAG_COMMAND_TYPE);
        Set<String> commandResults = tagValues(KairoMetricsCatalog.AGENT_COMMAND_TOTAL, KairoMetricsCatalog.TAG_RESULT);
        Set<String> operationTypes = tagValues(KairoMetricsCatalog.OPERATION_TOTAL, KairoMetricsCatalog.TAG_OPERATION_TYPE);

        assertThat(commandTypes).contains(KairoMetricsCatalog.OTHER).doesNotContain("NOT_A_REAL_COMMAND_TYPE");
        assertThat(commandResults).contains(KairoMetricsCatalog.OTHER).doesNotContain("WEIRD_RESULT");
        assertThat(operationTypes).contains(KairoMetricsCatalog.OTHER).doesNotContain("NOT_A_REAL_OPERATION_TYPE");
    }

    // -------------------------------------------------------- build info

    @Test
    void buildInfoGaugeIsOneAndSharesActuatorInfoIdentity() {
        Gauge gauge = registry.get(KairoMetricsCatalog.PLATFORM_BUILD_INFO).gauge();
        assertThat(gauge.value()).isEqualTo(1.0);

        BuildProperties build = buildPropertiesProvider.getIfAvailable();
        GitProperties git = gitPropertiesProvider.getIfAvailable();
        String expectedVersion = KairoBuildIdentity.version(build);
        String expectedCommit = KairoBuildIdentity.commit(git);
        assertThat(tagValue(gauge, KairoMetricsCatalog.TAG_VERSION)).isEqualTo(expectedVersion);
        assertThat(tagValue(gauge, KairoMetricsCatalog.TAG_COMMIT)).isEqualTo(expectedCommit);
    }

    // -------------------------------------------------------- real-path value changes

    @Test
    void operationLifecycleDrivesCounterAndTimer() {
        double before = counterCount(KairoMetricsCatalog.OPERATION_TOTAL,
                KairoMetricsCatalog.TAG_OPERATION_TYPE, OperationType.PREVIEW.name(),
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS");
        long timerBefore = timerCount(KairoMetricsCatalog.OPERATION_DURATION_SECONDS,
                KairoMetricsCatalog.TAG_OPERATION_TYPE, OperationType.PREVIEW.name(),
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS");
        driveOperationSuccess();
        assertThat(counterCount(KairoMetricsCatalog.OPERATION_TOTAL,
                KairoMetricsCatalog.TAG_OPERATION_TYPE, OperationType.PREVIEW.name(),
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS")).isGreaterThan(before);
        assertThat(timerCount(KairoMetricsCatalog.OPERATION_DURATION_SECONDS,
                KairoMetricsCatalog.TAG_OPERATION_TYPE, OperationType.PREVIEW.name(),
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS")).isGreaterThan(timerBefore);
    }

    @Test
    void reconcileDrivesCounter() {
        String agentId = freshAgent();
        double before = counterCount(KairoMetricsCatalog.RECONCILE_TOTAL,
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS");
        reconciliation.reconcileAgent(admin, agentId);
        assertThat(counterCount(KairoMetricsCatalog.RECONCILE_TOTAL,
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS")).isGreaterThan(before);
    }

    @Test
    void ttlCleanupDrivesCounter() {
        double before = counterCount(KairoMetricsCatalog.TTL_CLEANUP_TOTAL,
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS");
        Map<String, Object> result = scriptSessions.expireSessions();
        assertThat(result).containsKey("expired");
        assertThat(counterCount(KairoMetricsCatalog.TTL_CLEANUP_TOTAL,
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS")).isGreaterThan(before);
    }

    @Test
    void commandAckDrivesSuccessAndFailureCounters() {
        double successBefore = counterCount(KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                KairoMetricsCatalog.TAG_COMMAND_TYPE, "DISCOVER_TARGETS",
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS");
        driveCommandAck("ACKED", "SUCCESS");
        assertThat(counterCount(KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                KairoMetricsCatalog.TAG_COMMAND_TYPE, "DISCOVER_TARGETS",
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS")).isGreaterThan(successBefore);

        double failBefore = counterCount(KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                KairoMetricsCatalog.TAG_COMMAND_TYPE, "DISCOVER_TARGETS",
                KairoMetricsCatalog.TAG_RESULT, "FAILURE");
        driveCommandAck("FAILED", "FAILURE");
        assertThat(counterCount(KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                KairoMetricsCatalog.TAG_COMMAND_TYPE, "DISCOVER_TARGETS",
                KairoMetricsCatalog.TAG_RESULT, "FAILURE")).isGreaterThan(failBefore);
    }

    @Test
    void commandExhaustionDrivesTimeoutCounter() {
        double before = counterCount(KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                KairoMetricsCatalog.TAG_COMMAND_TYPE, KairoMetricsCatalog.OTHER,
                KairoMetricsCatalog.TAG_RESULT, "TIMEOUT");
        String agentId = freshAgent();
        RequestContext agentCtx = new RequestContext(agentId, "corr", "127.0.0.1", "agent", "test");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", "DISCOVER_TARGETS");
        request.put("maxAttempts", 1);
        String commandId = String.valueOf(commands.createManualCommand(admin, agentId, request).get("id"));
        commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        // Expire the in-flight lease so the next poll exhausts the command (attempts >= maxAttempts).
        jdbc.update("update agent_command set lease_expires_at = timestamp '2020-01-01 00:00:00' where id = ?", commandId);
        commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        assertThat(counterCount(KairoMetricsCatalog.AGENT_COMMAND_TOTAL,
                KairoMetricsCatalog.TAG_COMMAND_TYPE, KairoMetricsCatalog.OTHER,
                KairoMetricsCatalog.TAG_RESULT, "TIMEOUT")).isGreaterThan(before);
    }

    @Test
    void rollbackDrivesSuccessAndFailureCounters() {
        double successBefore = counterCount(KairoMetricsCatalog.ROLLBACK_TOTAL,
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS");
        driveRollback("UNLOADED");
        assertThat(counterCount(KairoMetricsCatalog.ROLLBACK_TOTAL,
                KairoMetricsCatalog.TAG_RESULT, "SUCCESS")).isGreaterThan(successBefore);

        double failBefore = counterCount(KairoMetricsCatalog.ROLLBACK_TOTAL,
                KairoMetricsCatalog.TAG_RESULT, "FAILURE");
        driveRollback("FAILED");
        assertThat(counterCount(KairoMetricsCatalog.ROLLBACK_TOTAL,
                KairoMetricsCatalog.TAG_RESULT, "FAILURE")).isGreaterThan(failBefore);
    }

    // -------------------------------------------------------- gauges reflect authoritative state

    @Test
    void gaugesReflectCurrentStateAfterRefresh() {
        String agentId = freshAgent();
        // Agent status gauge: the seeded agent is ACTIVE.
        stateProvider.refresh();
        double active = gaugeValue(KairoMetricsCatalog.AGENT_ONLINE, KairoMetricsCatalog.TAG_STATUS, "ACTIVE");
        assertThat(active).isGreaterThan(0.0);
        assertThat(stateProvider.lastRefreshAt()).isNotNull();

        // Mark it OFFLINE -> the gauge reflects the new authoritative state, never scanning per scrape.
        jdbc.update("update agent_instance set status = 'OFFLINE' where id = ?", agentId);
        stateProvider.refresh();
        assertThat(gaugeValue(KairoMetricsCatalog.AGENT_ONLINE, KairoMetricsCatalog.TAG_STATUS, "OFFLINE"))
                .isGreaterThan(0.0);

        // Command backlog gauge: an enqueued PENDING command shows up after refresh.
        String agentId2 = freshAgent();
        RequestContext agentCtx = new RequestContext(agentId2, "corr", "127.0.0.1", "agent", "test");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", "DISCOVER_TARGETS");
        request.put("maxAttempts", 5);
        commands.createManualCommand(admin, agentId2, request);
        stateProvider.refresh();
        assertThat(gaugeValue(KairoMetricsCatalog.AGENT_COMMAND_BACKLOG,
                KairoMetricsCatalog.TAG_STATUS, "PENDING",
                KairoMetricsCatalog.TAG_COMMAND_TYPE, "DISCOVER_TARGETS")).isGreaterThan(0.0);

        // Runtime rule targets gauge: a desired rule creates a target; set its drift state to FRESH.
        String instanceId = "inst-rt-" + UUID.randomUUID();
        String processStartId = "rt-host:" + UUID.randomUUID() + ":1";
        String ruleId = "rule-rt-" + UUID.randomUUID();
        long version = 1L;
        seedInstance(jdbc, "agent-rt-" + UUID.randomUUID(), instanceId, processStartId);
        seedDesiredRule(jdbc, ruleId, version, instanceId, TARGET_CLASS, TARGET_LOADER,
                "compute", "(I)I", "METHOD_ENTER",
                "{\"script\":\"return mock.returnValue(7)\",\"phase\":\"BEFORE\"}", "ACTIVE");
        jdbc.update("update rule_target set drift_status = 'FRESH' where rule_version_id = ?", ruleId + ":" + version);
        stateProvider.refresh();
        assertThat(gaugeValue(KairoMetricsCatalog.RUNTIME_RULE_TARGETS,
                KairoMetricsCatalog.TAG_STATE, "FRESH")).isGreaterThan(0.0);
    }

    @Test
    void gaugeNormalisesUnknownStateToOtherBucket() {
        String agentId = freshAgent();
        jdbc.update("update agent_instance set status = 'SURPRISE_STATUS' where id = ?", agentId);
        stateProvider.refresh();
        assertThat(gaugeValue(KairoMetricsCatalog.AGENT_ONLINE,
                KairoMetricsCatalog.TAG_STATUS, KairoMetricsCatalog.OTHER)).isGreaterThan(0.0);
        // No meter is ever created for the unknown raw status.
        assertThat(registry.find(KairoMetricsCatalog.AGENT_ONLINE).tag(KairoMetricsCatalog.TAG_STATUS, "SURPRISE_STATUS").meters())
                .isEmpty();

        jdbc.update("insert into agent_command(id, agent_id, command_type, status, idempotency_key, payload_json, "
                + "result_json, attempts, max_attempts, available_at, correlation_id, created_by, created_at, updated_at) "
                + "values (?, ?, 'NOPE_TYPE', 'PENDING', ?, '{}', '{}', 0, 5, current_timestamp, 'corr-fake', "
                + "'system', current_timestamp, current_timestamp)",
                "cmd-fake-" + UUID.randomUUID(), agentId, "idem-" + UUID.randomUUID());
        stateProvider.refresh();
        assertThat(gaugeValue(KairoMetricsCatalog.AGENT_COMMAND_BACKLOG,
                KairoMetricsCatalog.TAG_STATUS, "PENDING",
                KairoMetricsCatalog.TAG_COMMAND_TYPE, KairoMetricsCatalog.OTHER)).isGreaterThan(0.0);
        assertThat(registry.find(KairoMetricsCatalog.AGENT_COMMAND_BACKLOG)
                .tag(KairoMetricsCatalog.TAG_COMMAND_TYPE, "NOPE_TYPE").meters()).isEmpty();
    }

    @Test
    void databaseAggregatesAreBoundedBeforeJavaNormalisation() {
        String agentId = freshAgent();
        for (int i = 0; i < 40; i++) {
            String suffix = UUID.randomUUID().toString();
            String rawAgentId = "agent-raw-" + suffix;
            seedInstance(jdbc, rawAgentId, "inst-raw-" + suffix, "raw-host:" + suffix + ":1");
            jdbc.update("update agent_instance set status = ? where id = ?", "RAW_AGENT_STATUS_" + i, rawAgentId);
            jdbc.update("insert into agent_command(id, agent_id, command_type, status, idempotency_key, payload_json, "
                            + "result_json, attempts, max_attempts, available_at, correlation_id, created_by, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, '{}', '{}', 0, 5, current_timestamp, 'corr-raw', "
                            + "'system', current_timestamp, current_timestamp)",
                    "cmd-raw-" + suffix, agentId, "RAW_COMMAND_TYPE_" + i, "RAW_COMMAND_STATUS_" + i,
                    "idem-raw-" + suffix);
        }

        List<Map<String, Object>> agents = metricsMapper.countAgentsByStatus();
        List<Map<String, Object>> commands = metricsMapper.countCommandsByStatusAndType();
        assertThat(agents).hasSizeLessThanOrEqualTo(KairoMetricsCatalog.AGENT_STATUSES.size());
        assertThat(commands).hasSizeLessThanOrEqualTo(
                KairoMetricsCatalog.COMMAND_STATUSES.size() * (KairoMetricsCatalog.COMMAND_TYPES.size() + 1));
        assertThat(columnValues(agents, "status")).isSubsetOf(KairoMetricsCatalog.AGENT_STATUSES);
        Set<String> commandTypes = new java.util.HashSet<>(KairoMetricsCatalog.COMMAND_TYPES);
        commandTypes.add(KairoMetricsCatalog.OTHER);
        assertThat(columnValues(commands, "status")).isSubsetOf(KairoMetricsCatalog.COMMAND_STATUSES);
        assertThat(columnValues(commands, "command_type")).isSubsetOf(commandTypes);
    }

    // -------------------------------------------------------- helpers driving real paths

    private void driveOperationSuccess() {
        String opId = operationService.start(new OperationService.StartRequest(
                OperationType.PREVIEW, "rule", "r-" + UUID.randomUUID(), null, null, "system", "corr", null, null));
        operationService.succeed(opId, Map.of("ok", true));
    }

    private void driveReconcile() {
        reconciliation.reconcileAgent(admin, freshAgent());
    }

    private void driveTtlCleanup() {
        scriptSessions.expireSessions();
    }

    private void driveCommandAck(String ackStatus, String resultTag) {
        String agentId = freshAgent();
        RequestContext agentCtx = new RequestContext(agentId, "corr", "127.0.0.1", "agent", "test");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", "DISCOVER_TARGETS");
        request.put("maxAttempts", 5);
        String commandId = String.valueOf(commands.createManualCommand(admin, agentId, request).get("id"));
        commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", ackStatus);
        ack.put("expectedAttempts", 1);
        ack.put("result", Map.of());
        if ("FAILED".equals(ackStatus)) {
            ack.put("errorMessage", "agent reported failure");
        }
        commands.ack(commandId, agentCtx, ack);
    }

    private void driveRollback(String executionStatus) {
        String n = UUID.randomUUID().toString();
        String agentId = "agent-rb-" + n;
        String instanceId = "inst-rb-" + n;
        String processStartId = "rb-host:" + n + ":1";
        String operationId = "op-rb-" + n;
        String rollbackId = "rb-exec-" + n;
        String ruleId = "rule-rb-" + n;
        seedInstance(jdbc, agentId, instanceId, processStartId);
        seedDesiredRule(jdbc, ruleId, 1, instanceId, TARGET_CLASS, TARGET_LOADER,
                "compute", "(I)I", "METHOD_ENTER",
                "{\"script\":\"return mock.returnValue(7)\",\"phase\":\"BEFORE\"}", "ACTIVE");
        seedSucceededOperation(jdbc, operationId, ruleId, 1);
        jdbc.update("update operation_plan set status = 'UNLOADING' where id = ?", operationId);
        seedExecution(jdbc, "exec-rb-" + n, operationId, instanceId, 1, executionStatus);
        jdbc.update("insert into rollback_execution(id, operation_plan_id, rollback_type, status, reason, "
                + "target_class_id, target_class_name, created_by, created_at, finished_at) "
                + "values (?, ?, 'RESET_CLASS', 'DISPATCHED', ?, ?, ?, 'system', current_timestamp, null)",
                rollbackId, operationId, "unload", "0", TARGET_CLASS);
        commands.tryCompleteUnload(admin, rollbackId, operationId);
    }

    private String freshAgent() {
        String n = UUID.randomUUID().toString();
        String agentId = "agent-metrics-" + n;
        seedInstance(jdbc, agentId, "inst-metrics-" + n, "host:" + n + ":1");
        return agentId;
    }

    // -------------------------------------------------------- meter-inspection helpers

    private Set<String> meterNames() {
        return kairoMeters().stream().map(m -> m.getId().getName()).collect(Collectors.toSet());
    }

    private Set<String> gaugeNames() {
        return kairoMeters().stream().filter(m -> m instanceof Gauge)
                .map(m -> m.getId().getName()).collect(Collectors.toSet());
    }

    private Set<String> counterNames() {
        return kairoMeters().stream().filter(m -> m instanceof Counter)
                .map(m -> m.getId().getName()).collect(Collectors.toSet());
    }

    private Set<String> timerNames() {
        return kairoMeters().stream().filter(m -> m instanceof Timer)
                .map(m -> m.getId().getName()).collect(Collectors.toSet());
    }

    private List<Meter> kairoMeters() {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("kairo_"))
                .toList();
    }

    private List<Meter> meters(String name) {
        return registry.find(name).meters().stream().toList();
    }

    private Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
    }

    private Set<String> tagValues(String name, String key) {
        return meters(name).stream().flatMap(m -> m.getId().getTags().stream())
                .filter(t -> t.getKey().equals(key)).map(Tag::getValue).collect(Collectors.toSet());
    }

    private Set<String> columnValues(List<Map<String, Object>> rows, String column) {
        return rows.stream().map(row -> row.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(column))
                        .map(entry -> String.valueOf(entry.getValue()))
                        .findFirst().orElse(null))
                .collect(Collectors.toSet());
    }

    private String tagValue(Gauge gauge, String key) {
        return gauge.getId().getTags().stream().filter(t -> t.getKey().equals(key))
                .map(Tag::getValue).findFirst().orElse(null);
    }

    private Set<String> allowedKeysFor(String name) {
        return switch (name) {
            case KairoMetricsCatalog.AGENT_ONLINE -> KairoMetricsCatalog.TAGS_AGENT_ONLINE;
            case KairoMetricsCatalog.AGENT_COMMAND_BACKLOG -> KairoMetricsCatalog.TAGS_AGENT_COMMAND_BACKLOG;
            case KairoMetricsCatalog.AGENT_COMMAND_TOTAL -> KairoMetricsCatalog.TAGS_AGENT_COMMAND_TOTAL;
            case KairoMetricsCatalog.OPERATION_TOTAL -> KairoMetricsCatalog.TAGS_OPERATION_TOTAL;
            case KairoMetricsCatalog.OPERATION_DURATION_SECONDS -> KairoMetricsCatalog.TAGS_OPERATION_DURATION;
            case KairoMetricsCatalog.RUNTIME_RULE_TARGETS -> KairoMetricsCatalog.TAGS_RUNTIME_RULE_TARGETS;
            case KairoMetricsCatalog.RECONCILE_TOTAL -> KairoMetricsCatalog.TAGS_RECONCILE_TOTAL;
            case KairoMetricsCatalog.ROLLBACK_TOTAL -> KairoMetricsCatalog.TAGS_ROLLBACK_TOTAL;
            case KairoMetricsCatalog.TTL_CLEANUP_TOTAL -> KairoMetricsCatalog.TAGS_TTL_CLEANUP_TOTAL;
            case KairoMetricsCatalog.PLATFORM_BUILD_INFO -> KairoMetricsCatalog.TAGS_PLATFORM_BUILD_INFO;
            default -> throw new IllegalArgumentException("unknown meter " + name);
        };
    }

    private double counterCount(String name, String... kv) {
        var search = registry.find(name);
        for (int i = 0; i < kv.length; i += 2) {
            search = search.tag(kv[i], kv[i + 1]);
        }
        Counter counter = search.counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long timerCount(String name, String... kv) {
        var search = registry.find(name);
        for (int i = 0; i < kv.length; i += 2) {
            search = search.tag(kv[i], kv[i + 1]);
        }
        Timer timer = search.timer();
        return timer == null ? 0L : timer.count();
    }

    private double gaugeValue(String name, String... kv) {
        var search = registry.find(name);
        for (int i = 0; i < kv.length; i += 2) {
            search = search.tag(kv[i], kv[i + 1]);
        }
        Gauge gauge = search.gauge();
        return gauge == null ? 0.0 : gauge.value();
    }
}
