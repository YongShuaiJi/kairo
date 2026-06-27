package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.domain.ApprovalStatus;
import com.example.runtimemock.platform.domain.ExtractionTaskStatus;
import com.example.runtimemock.platform.domain.OperationPlanStatus;
import com.example.runtimemock.platform.domain.PlanStatus;
import com.example.runtimemock.platform.domain.RecordingSessionStatus;
import com.example.runtimemock.platform.domain.ReplayExecutionStatus;
import com.example.runtimemock.platform.fencing.FencingTokenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PlatformJdbcService {

    private static final String GENESIS_HASH = "GENESIS";
    private static final long AUDIT_CHAIN_LOCK_ID = 0x52554E54494D454DL;

    private final JdbcTemplate jdbcTemplate;
    private final RbacService rbacService;
    private final FencingTokenService fencingTokenService;
    private final Clock clock;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Autowired
    public PlatformJdbcService(JdbcTemplate jdbcTemplate, RbacService rbacService,
                               FencingTokenService fencingTokenService) {
        this(jdbcTemplate, rbacService, fencingTokenService, Clock.systemUTC());
    }

    PlatformJdbcService(JdbcTemplate jdbcTemplate, RbacService rbacService,
                        FencingTokenService fencingTokenService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.rbacService = rbacService;
        this.fencingTokenService = fencingTokenService;
        this.clock = clock;
    }

    public Map<String, Object> health() {
        long started = System.nanoTime();
        jdbcTemplate.queryForObject("select 1", Integer.class);
        long latencyMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("platformApi", Map.of("status", "UP", "latencyMs", 0));
        services.put("postgresql", Map.of("status", "UP", "latencyMs", latencyMs));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("checkedAt", clock.instant().toString());
        result.put("storage", "postgresql");
        result.put("services", services);
        result.put("recordingSessionCount", count("recording_session"));
        result.put("datasetVersionCount", count("dataset_version"));
        result.put("replayPlanCount", count("replay_plan"));
        result.put("approvalCount", count("approval_request"));
        result.put("outboxPendingCount", countWhere("outbox_event", "status = 'NEW'"));
        return result;
    }

    public List<Map<String, Object>> list(String table, String orderBy) {
        return normalizeRows(jdbcTemplate.queryForList(
                "select * from " + table + " order by " + orderBy + " limit 1000"));
    }

    public List<Map<String, Object>> listFencingTokens() {
        return normalizeRows(jdbcTemplate.queryForList("""
                select id, resource_type, resource_id, purpose, sequence, owner, status,
                       lease_expires_at, created_at, consumed_at, correlation_id
                  from fencing_token
                 order by created_at desc, id
                 limit 1000
                """));
    }

    public List<Map<String, Object>> listAgents() {
        return normalizeRows(jdbcTemplate.queryForList("""
                select id, instance_id, sidecar_id, status, agent_version, bootstrap_version,
                       listen_host, listen_port, capabilities_json, last_heartbeat_at, created_at, updated_at
                  from agent_instance
                 order by created_at, id
                 limit 1000
                """));
    }

    public List<Map<String, Object>> listDatasources() {
        return normalizeRows(jdbcTemplate.queryForList("""
                select id, application_id, environment_id, datasource_type, name, status,
                       created_by, created_at, updated_at
                  from datasource_registration
                 order by created_at, id
                 limit 1000
                """));
    }

    @Transactional
    public Map<String, Object> issueFencingToken(RequestContext context, Map<String, Object> request) {
        String resourceType = requiredString(request, "resourceType", null);
        String resourceId = requiredString(request, "resourceId", null);
        String purpose = requiredString(request, "purpose", "state-transition");
        rbacService.require(context, fencingCapability(resourceType));
        long ttlSeconds = optionalLong(request, "ttlSeconds", 300);
        Map<String, Object> token = fencingTokenService.issue(context, resourceType, resourceId, purpose, ttlSeconds);
        auditAndOutbox(context, "fencing_token.issue", resourceType, resourceId, 1,
                "", hash(token), "SUCCESS", optionalString(request, "reason", "issue fencing token"),
                Map.of("purpose", purpose, "sequence", token.get("sequence")));
        return token;
    }

    @Transactional
    public Map<String, Object> createInstance(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "INSTANCE_MANAGE");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "instance-" + UUID.randomUUID());
        String applicationId = requiredString(request, "applicationId", null);
        String environmentId = optionalString(request, "environmentId", null);
        requireExists("application", applicationId);
        if (environmentId != null) {
            validateEnvironment(applicationId, environmentId);
        }
        jdbcTemplate.update("""
                insert into instance(
                    id, application_id, environment_id, hostname, process_id, runtime, status,
                    labels_json, last_seen_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                applicationId,
                environmentId,
                requiredString(request, "hostname", null),
                requiredString(request, "processId", "unknown"),
                requiredString(request, "runtime", "java"),
                requiredString(request, "status", "ACTIVE"),
                jsonValue(request, "labels", Map.of()),
                timestamp(now),
                timestamp(now),
                timestamp(now));
        insertLabels(id, optionalMap(request, "labels"), now);
        auditAndOutbox(context, "instance.create", "instance", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create instance"),
                Map.of("hostname", requiredString(request, "hostname", null)));
        return getById("instance", id);
    }

    @Transactional
    public Map<String, Object> registerAgentRuntime(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        RegistrationApplication registrationApplication = resolveRegistrationApplication(request);
        String applicationId = registrationApplication.applicationId();
        String environmentId = optionalString(request, "environmentId", null);
        if (environmentId != null) {
            validateEnvironment(applicationId, environmentId);
        }
        String processStartId = requiredString(request, "processStartId", null);
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plusSeconds(30);
        List<Map<String, Object>> existingInstances = jdbcTemplate.queryForList(
                "select * from instance where process_start_id = ?", processStartId);
        String instanceId;
        if (existingInstances.isEmpty()) {
            instanceId = "instance-" + UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into instance(
                        id, application_id, environment_id, hostname, process_id, runtime, status,
                        labels_json, last_seen_at, created_at, updated_at, process_start_id,
                        jvm_started_at, java_version, load_mode, agent_version, capabilities_json,
                        lease_expires_at, registration_status
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    instanceId,
                    applicationId,
                    environmentId,
                    requiredString(request, "hostname", null),
                    requiredString(request, "processId", null),
                    requiredString(request, "runtime", "java"),
                    environmentId == null ? "PENDING_ASSIGNMENT" : "ACTIVE",
                    jsonValue(request, "labels", Map.of()),
                    timestamp(now),
                    timestamp(now),
                    timestamp(now),
                    processStartId,
                    timestamp(Instant.ofEpochMilli(optionalLong(request, "jvmStartedAtEpochMillis",
                            now.toEpochMilli()))),
                    requiredString(request, "javaVersion", "unknown"),
                    requiredString(request, "loadMode", "unknown"),
                    requiredString(request, "agentVersion", "unknown"),
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(leaseExpiresAt),
                    environmentId == null ? "PENDING_ASSIGNMENT" : "ASSIGNED");
        } else {
            Map<String, Object> existing = normalizeRow(existingInstances.get(0));
            instanceId = String.valueOf(existing.get("id"));
            jdbcTemplate.update("""
                    update instance
                       set application_id = ?,
                           environment_id = coalesce(?, environment_id),
                           hostname = ?,
                           process_id = ?,
                           runtime = ?,
                           status = case when coalesce(?, environment_id) is null
                                         then 'PENDING_ASSIGNMENT' else 'ACTIVE' end,
                           last_seen_at = ?,
                           updated_at = ?,
                           java_version = ?,
                           load_mode = ?,
                           agent_version = ?,
                           capabilities_json = ?,
                           lease_expires_at = ?,
                           registration_status = case when coalesce(?, environment_id) is null
                                                      then 'PENDING_ASSIGNMENT' else 'ASSIGNED' end
                     where id = ?
                    """, applicationId, environmentId,
                    requiredString(request, "hostname", null),
                    requiredString(request, "processId", null),
                    requiredString(request, "runtime", "java"),
                    environmentId,
                    timestamp(now), timestamp(now),
                    requiredString(request, "javaVersion", "unknown"),
                    requiredString(request, "loadMode", "unknown"),
                    requiredString(request, "agentVersion", "unknown"),
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(leaseExpiresAt), environmentId, instanceId);
        }

        List<Map<String, Object>> existingAgents = jdbcTemplate.queryForList(
                "select * from agent_instance where instance_id = ? order by created_at limit 1", instanceId);
        String agentId;
        if (existingAgents.isEmpty()) {
            agentId = "agent-" + UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into agent_instance(
                        id, instance_id, sidecar_id, status, agent_version, bootstrap_version,
                        listen_host, listen_port, token_hash, capabilities_json,
                        last_heartbeat_at, created_at, updated_at, lease_expires_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, agentId, instanceId, null, "ACTIVE",
                    requiredString(request, "agentVersion", "unknown"),
                    requiredString(request, "bootstrapVersion", "embedded"),
                    requiredString(request, "listenHost", "127.0.0.1"),
                    (int) optionalLong(request, "listenPort", 0),
                    "platform-authenticated",
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(now), timestamp(now), timestamp(now), timestamp(leaseExpiresAt));
        } else {
            agentId = String.valueOf(normalizeRow(existingAgents.get(0)).get("id"));
            jdbcTemplate.update("""
                    update agent_instance
                       set status = 'ACTIVE',
                           agent_version = ?,
                           bootstrap_version = ?,
                           listen_host = ?,
                           listen_port = ?,
                           capabilities_json = ?,
                           last_heartbeat_at = ?,
                           updated_at = ?,
                           lease_expires_at = ?
                     where id = ?
                    """, requiredString(request, "agentVersion", "unknown"),
                    requiredString(request, "bootstrapVersion", "embedded"),
                    requiredString(request, "listenHost", "127.0.0.1"),
                    (int) optionalLong(request, "listenPort", 0),
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(now), timestamp(now), timestamp(leaseExpiresAt), agentId);
            jdbcTemplate.update("delete from agent_capability where agent_id = ?", agentId);
        }
        insertAgentCapabilities(agentId, optionalList(request, "capabilities"), now);
        auditAndOutbox(context, "agent_instance.self_register", "agent_instance", agentId, 1,
                "", hash(request), "SUCCESS", "Agent 运行时自动注册",
                Map.of("instanceId", instanceId, "applicationId", applicationId,
                        "environmentAssigned", environmentId != null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("agentId", agentId);
        result.put("applicationId", applicationId);
        result.put("projectName", registrationApplication.projectName());
        result.put("applicationName", registrationApplication.applicationName());
        result.put("environmentId", environmentId);
        result.put("status", environmentId == null ? "PENDING_ASSIGNMENT" : "ACTIVE");
        result.put("leaseExpiresAt", leaseExpiresAt.toString());
        return result;
    }

    private RegistrationApplication resolveRegistrationApplication(Map<String, Object> request) {
        String applicationId = optionalString(request, "applicationId", null);
        if (applicationId != null) {
            Map<String, Object> application = getById("application", applicationId);
            String projectId = String.valueOf(application.get("project_id"));
            Map<String, Object> project = getById("project", projectId);
            return new RegistrationApplication(applicationId, String.valueOf(project.get("name")),
                    String.valueOf(application.get("name")));
        }

        String projectName = requiredString(request, "projectName", null);
        String applicationName = requiredString(request, "applicationName", null);
        String projectId = jdbcTemplate.query("""
                        select id from project
                         where organization_id = 'org-default' and name = ?
                         order by created_at
                         limit 1
                        """, resultSet -> resultSet.next() ? resultSet.getString(1) : null, projectName);
        if (projectId == null) {
            projectId = "project-" + UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into project(id, organization_id, name, created_at)
                    values (?, 'org-default', ?, ?)
                    """, projectId, projectName, timestamp(clock.instant()));
        }

        String resolvedProjectId = projectId;
        String resolvedApplicationId = jdbcTemplate.query("""
                        select id from application
                         where project_id = ? and name = ?
                         order by created_at
                         limit 1
                        """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                resolvedProjectId, applicationName);
        if (resolvedApplicationId == null) {
            resolvedApplicationId = "application-" + UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into application(id, project_id, name, created_at)
                    values (?, ?, ?, ?)
                    """, resolvedApplicationId, resolvedProjectId, applicationName,
                    timestamp(clock.instant()));
        }
        ensureStandardEnvironments(resolvedApplicationId);
        return new RegistrationApplication(resolvedApplicationId, projectName, applicationName);
    }

    private void ensureStandardEnvironments(String applicationId) {
        for (String type : List.of("DEV", "SIT", "UAT", "PROD")) {
            Integer count = jdbcTemplate.queryForObject("""
                    select count(*) from environment
                     where application_id = ? and type = ?
                    """, Integer.class, applicationId, type);
            if (count != null && count > 0) {
                continue;
            }
            jdbcTemplate.update("""
                    insert into environment(id, application_id, name, type, created_at)
                    values (?, ?, ?, ?, ?)
                    """, "environment-" + UUID.randomUUID(), applicationId,
                    type.toLowerCase(Locale.ROOT), type, timestamp(clock.instant()));
        }
    }

    @Transactional
    public Map<String, Object> assignInstanceEnvironment(String instanceId, RequestContext context,
                                                         Map<String, Object> request) {
        rbacService.require(context, "INSTANCE_MANAGE");
        Map<String, Object> instance = getById("instance", instanceId);
        String environmentId = requiredString(request, "environmentId", null);
        validateEnvironment(String.valueOf(instance.get("application_id")), environmentId);
        Instant now = clock.instant();
        jdbcTemplate.update("""
                update instance
                   set environment_id = ?, status = 'ACTIVE', registration_status = 'ASSIGNED',
                       updated_at = ?
                 where id = ?
                """, environmentId, timestamp(now), instanceId);
        auditAndOutbox(context, "instance.assign_environment", "instance", instanceId, 1,
                hash(instance), hash(Map.of("environmentId", environmentId)), "SUCCESS",
                "分配运行环境", Map.of("environmentId", environmentId));
        return getById("instance", instanceId);
    }

    @Transactional
    public Map<String, Object> createSidecar(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "sidecar-" + UUID.randomUUID());
        jdbcTemplate.update("""
                insert into sidecar_instance(
                    id, instance_id, status, sidecar_version, endpoint, capabilities_json,
                    last_heartbeat_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                optionalString(request, "instanceId", null),
                requiredString(request, "status", "ACTIVE"),
                requiredString(request, "sidecarVersion", "unknown"),
                requiredString(request, "endpoint", null),
                jsonValue(request, "capabilities", List.of()),
                timestamp(now),
                timestamp(now),
                timestamp(now));
        auditAndOutbox(context, "sidecar_instance.create", "sidecar_instance", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create sidecar"),
                Map.of("endpoint", requiredString(request, "endpoint", null)));
        return getById("sidecar_instance", id);
    }

    @Transactional
    public Map<String, Object> createAgent(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "agent-" + UUID.randomUUID());
        jdbcTemplate.update("""
                insert into agent_instance(
                    id, instance_id, sidecar_id, status, agent_version, bootstrap_version,
                    listen_host, listen_port, token_hash, capabilities_json,
                    last_heartbeat_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                optionalString(request, "instanceId", null),
                optionalString(request, "sidecarId", null),
                requiredString(request, "status", "ACTIVE"),
                requiredString(request, "agentVersion", "unknown"),
                requiredString(request, "bootstrapVersion", "unknown"),
                requiredString(request, "listenHost", "127.0.0.1"),
                (int) optionalLong(request, "listenPort", 0),
                requiredString(request, "tokenHash", "unregistered"),
                jsonValue(request, "capabilities", List.of()),
                timestamp(now),
                timestamp(now),
                timestamp(now));
        insertAgentCapabilities(id, optionalList(request, "capabilities"), now);
        auditAndOutbox(context, "agent_instance.create", "agent_instance", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create agent"),
                Map.of("status", requiredString(request, "status", "ACTIVE")));
        return safeAgent(getById("agent_instance", id));
    }

    @Transactional
    public Map<String, Object> recordAgentHeartbeat(String id, RequestContext context, Map<String, Object> request) {
        if (!(id.equals(context.actor()) && "agent".equals(context.identitySource()))) {
            rbacService.require(context, "AGENT_MANAGE");
        }
        getById("agent_instance", id);
        Instant now = clock.instant();
        String status = requiredString(request, "status", "ACTIVE");
        Instant leaseExpiresAt = now.plusSeconds(30);
        jdbcTemplate.update("""
                update agent_instance
                   set status = ?, last_heartbeat_at = ?, updated_at = ?, lease_expires_at = ?
                 where id = ?
                """, status, timestamp(now), timestamp(now), timestamp(leaseExpiresAt), id);
        jdbcTemplate.update("""
                update instance
                   set status = case when environment_id is null then 'PENDING_ASSIGNMENT' else 'ACTIVE' end,
                       last_seen_at = ?, updated_at = ?, lease_expires_at = ?
                 where id = (select instance_id from agent_instance where id = ?)
                """, timestamp(now), timestamp(now), timestamp(leaseExpiresAt), id);
        jdbcTemplate.update("""
                insert into agent_heartbeat(id, agent_id, status, metrics_json, received_at)
                values (?, ?, ?, ?, ?)
                """, "agent-heartbeat-" + UUID.randomUUID(), id, status,
                jsonValue(request, "metrics", Map.of()), timestamp(now));
        auditAndOutbox(context, "agent_instance.heartbeat", "agent_instance", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "agent heartbeat"),
                Map.of("status", status));
        return safeAgent(getById("agent_instance", id));
    }

    @Transactional
    public Map<String, Object> createRule(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "RULE_MANAGE");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "rule-" + UUID.randomUUID());
        ApplicationEnvironment scope = requireApplicationEnvironment(request);
        jdbcTemplate.update("""
                insert into rule(
                    id, application_id, environment_id, name, status, current_draft_version, latest_version,
                    created_by, created_at, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                scope.applicationId(),
                scope.environmentId(),
                requiredString(request, "name", null),
                requiredString(request, "status", "DRAFT"),
                1L,
                1L,
                context.actor(),
                timestamp(now),
                context.actor(),
                timestamp(now));
        insertRuleVersion(context, id, 1L, request, now);
        auditAndOutbox(context, "rule.create", "rule", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create rule"),
                Map.of("version", 1L));
        return getById("rule", id);
    }

    @Transactional
    public Map<String, Object> createRuleVersion(String ruleId, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "RULE_MANAGE");
        Map<String, Object> rule = getById("rule", ruleId);
        long version = nextScopedVersion("rule_version", "rule_id", ruleId);
        Instant now = clock.instant();
        insertRuleVersion(context, ruleId, version, request, now);
        jdbcTemplate.update("""
                update rule
                   set current_draft_version = ?, latest_version = ?, updated_by = ?, updated_at = ?
                 where id = ?
                """, version, version, context.actor(), timestamp(now), ruleId);
        auditAndOutbox(context, "rule_version.create", "rule", ruleId, version,
                hash(rule), hash(request), "SUCCESS", optionalString(request, "reason", "create rule version"),
                Map.of("version", version));
        return getById("rule_version", ruleId + ":" + version);
    }

    @Transactional
    public Map<String, Object> createOperationPlan(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "ROLLOUT_MANAGE");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "operation-" + UUID.randomUUID());
        String applicationId = requiredString(request, "applicationId", null);
        String environmentId = requiredString(request, "environmentId", null);
        String resourceType = requiredString(request, "resourceType", null);
        String resourceId = requiredString(request, "resourceId", null);
        long resourceVersion = requiredLong(request, "resourceVersion");
        requireExists("application", applicationId);
        validateEnvironment(applicationId, environmentId);
        validateRolloutResource(applicationId, environmentId, resourceType, resourceId, resourceVersion);
        jdbcTemplate.update("""
                insert into operation_plan(
                    id, application_id, environment_id, plan_type, resource_type, resource_id, resource_version,
                    status, version, strategy_json, approval_id, created_by, created_at, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                applicationId,
                environmentId,
                requiredString(request, "planType", "RULE_ROLLOUT"),
                resourceType,
                resourceId,
                resourceVersion,
                OperationPlanStatus.DRAFT.name(),
                1L,
                jsonValue(request, "strategy", Map.of(
                        "targetMode", "ALL_ACTIVE_INSTANCES",
                        "automaticRollback", true
                )),
                optionalString(request, "approvalId", null),
                context.actor(),
                timestamp(now),
                context.actor(),
                timestamp(now));
        auditAndOutbox(context, "operation_plan.create", "operation_plan", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create operation plan"),
                Map.of("status", OperationPlanStatus.DRAFT.name()));
        return getById("operation_plan", id);
    }

    @Transactional
    public Map<String, Object> transitionOperationPlan(String id, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "ROLLOUT_MANAGE");
        Map<String, Object> original = getById("operation_plan", id);
        Map<String, Object> current = original;
        OperationPlanStatus expectedStatus = enumValue(request, "expectedStatus", OperationPlanStatus.class);
        OperationPlanStatus targetStatus = enumValue(request, "targetStatus", OperationPlanStatus.class);
        long expectedVersion = requiredLong(request, "expectedVersion");
        OperationPlanStatus currentStatus = OperationPlanStatus.valueOf(String.valueOf(current.get("status")));
        long currentVersion = ((Number) current.get("version")).longValue();
        assertExpected(currentStatus, expectedStatus, currentVersion, expectedVersion);
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw PlatformException.conflict("OPERATION_PLAN_INVALID_TRANSITION",
                    "Cannot transition operation plan from " + currentStatus + " to " + targetStatus,
                    Map.of("id", id, "currentStatus", currentStatus.name(), "targetStatus", targetStatus.name()));
        }
        String fencingToken = requiredString(request, "fencingToken", null);
        fencingTokenService.consume(context, "operation_plan", id, fencingToken);
        String reason = requiredString(request, "reason", null);
        if (targetStatus == OperationPlanStatus.APPROVED) {
            current = ensureSelfApprovalForOperation(id, current, context, reason);
            currentVersion = ((Number) current.get("version")).longValue();
            requireApprovedApproval("OPERATION_PLAN", id, currentVersion, current);
        }
        long newVersion = currentVersion + 1;
        Instant now = clock.instant();
        String approvalId = targetStatus == OperationPlanStatus.WAITING_APPROVAL
                ? "approval-" + UUID.randomUUID()
                : optionalString(current, "approval_id", null);
        if (targetStatus == OperationPlanStatus.WAITING_APPROVAL) {
            // approval_id has a foreign key to approval_request. Insert the approval
            // shell first, then bind it to the versioned operation plan below.
            createApprovalRecord(approvalId, "OPERATION_PLAN", id, newVersion,
                    current, context.actor(), reason, now);
        }
        int updated = jdbcTemplate.update("""
                    update operation_plan
                       set status = ?, version = ?, approval_id = ?, updated_by = ?, updated_at = ?
                     where id = ? and status = ? and version = ?
                    """, targetStatus.name(), newVersion, approvalId, context.actor(), timestamp(now),
                id, expectedStatus.name(), expectedVersion);
        if (updated == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "发布计划状态或版本已发生变化，请刷新后重试",
                    Map.of("id", id, "expectedStatus", expectedStatus.name(),
                            "expectedVersion", expectedVersion));
        }
        Map<String, Object> updatedPlan = getById("operation_plan", id);
        if (targetStatus == OperationPlanStatus.WAITING_APPROVAL) {
            jdbcTemplate.update("""
                    update approval_request
                       set subject_hash = ?, updated_at = ?
                     where id = ?
                    """, hash(updatedPlan), timestamp(now), approvalId);
        }
        if (targetStatus == OperationPlanStatus.APPROVED) {
            approveRuleVersionForOperation(updatedPlan, context, now);
        }
        auditAndOutbox(context, "operation_plan.transition", "operation_plan", id, newVersion,
                hash(original), hash(updatedPlan),
                "SUCCESS", reason,
                Map.of("from", expectedStatus.name(), "to", targetStatus.name(),
                        "fencingToken", fencingToken));
        return updatedPlan;
    }

    @Transactional
    public Map<String, Object> createRecordingRule(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "RECORD_ARGUMENTS");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "recording-rule-" + UUID.randomUUID());
        ApplicationEnvironment scope = requireApplicationEnvironment(request);
        jdbcTemplate.update("""
                insert into recording_rule(
                    id, application_id, environment_id, name, status, current_draft_version, latest_version,
                    created_by, created_at, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id,
                scope.applicationId(),
                scope.environmentId(),
                requiredString(request, "name", null),
                requiredString(request, "status", "DRAFT"),
                1L,
                1L,
                context.actor(),
                timestamp(now),
                context.actor(),
                timestamp(now));
        insertRecordingRuleVersion(context, id, 1L, request, now);
        auditAndOutbox(context, "recording_rule.create", "recording_rule", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create recording rule"),
                Map.of("version", 1L));
        return getById("recording_rule", id);
    }

    @Transactional
    public Map<String, Object> createRecordingRuleVersion(String recordingRuleId, RequestContext context,
                                                          Map<String, Object> request) {
        rbacService.require(context, "RECORD_ARGUMENTS");
        Map<String, Object> rule = getById("recording_rule", recordingRuleId);
        long version = nextScopedVersion("recording_rule_version", "recording_rule_id", recordingRuleId);
        Instant now = clock.instant();
        insertRecordingRuleVersion(context, recordingRuleId, version, request, now);
        jdbcTemplate.update("""
                update recording_rule
                   set current_draft_version = ?, latest_version = ?, updated_by = ?, updated_at = ?
                 where id = ?
                """, version, version, context.actor(), timestamp(now), recordingRuleId);
        auditAndOutbox(context, "recording_rule_version.create", "recording_rule", recordingRuleId, version,
                hash(rule), hash(request), "SUCCESS", optionalString(request, "reason", "create recording rule version"),
                Map.of("version", version));
        return getById("recording_rule_version", recordingRuleId + ":" + version);
    }

    @Transactional
    public Map<String, Object> createDatasource(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "DATA_EXTRACT");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "datasource-" + UUID.randomUUID());
        ApplicationEnvironment scope = requireApplicationEnvironment(request);
        jdbcTemplate.update("""
                insert into datasource_registration(
                    id, application_id, environment_id, datasource_type, name, status, config_json,
                    created_by, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id,
                scope.applicationId(),
                scope.environmentId(),
                requiredString(request, "datasourceType", "POSTGRESQL"),
                requiredString(request, "name", null),
                requiredString(request, "status", "ACTIVE"),
                jsonValue(request, "config", Map.of()),
                context.actor(),
                timestamp(now),
                timestamp(now));
        Map<String, Object> credential = optionalMap(request, "credential");
        if (!credential.isEmpty()) {
            jdbcTemplate.update("""
                    insert into datasource_credential_ref(id, datasource_id, provider, secret_ref, created_by, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, "datasource-credential-" + UUID.randomUUID(), id,
                    requiredString(credential, "provider", "VAULT"),
                    requiredString(credential, "secretRef", null),
                    context.actor(), timestamp(now));
        }
        auditAndOutbox(context, "datasource_registration.create", "datasource_registration", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create datasource"),
                Map.of("datasourceType", requiredString(request, "datasourceType", "POSTGRESQL")));
        return safeDatasource(getById("datasource_registration", id));
    }

    @Transactional
    public Map<String, Object> createExtractionTemplate(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "DATA_EXTRACT");
        String datasourceId = requiredString(request, "datasourceId", null);
        getById("datasource_registration", datasourceId);
        Instant now = clock.instant();
        String id = optionalString(request, "id", "extraction-template-" + UUID.randomUUID());
        jdbcTemplate.update("""
                insert into extraction_template(
                    id, datasource_id, name, status, current_draft_version, latest_version,
                    created_by, created_at, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, datasourceId, requiredString(request, "name", null),
                requiredString(request, "status", "DRAFT"), 1L, 1L,
                context.actor(), timestamp(now), context.actor(), timestamp(now));
        insertExtractionTemplateVersion(context, id, 1L, request, now);
        auditAndOutbox(context, "extraction_template.create", "extraction_template", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create extraction template"),
                Map.of("datasourceId", datasourceId, "version", 1L));
        return getById("extraction_template", id);
    }

    @Transactional
    public Map<String, Object> createExtractionTask(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "DATA_EXTRACT");
        String templateId = requiredString(request, "templateId", null);
        long templateVersion = requiredLong(request, "templateVersion");
        getExtractionTemplateVersion(templateId, templateVersion);
        Instant now = clock.instant();
        String id = optionalString(request, "id", "extraction-task-" + UUID.randomUUID());
        jdbcTemplate.update("""
                insert into extraction_task(
                    id, template_id, template_version, dataset_id, status, version, parameters_json,
                    quota_json, created_by, created_at, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, templateId, templateVersion, optionalString(request, "datasetId", null),
                ExtractionTaskStatus.DRAFT.name(), 1L, jsonValue(request, "parameters", Map.of()),
                jsonValue(request, "quota", Map.of("maxRows", 10_000, "timeoutSeconds", 5)),
                context.actor(), timestamp(now), context.actor(), timestamp(now));
        jdbcTemplate.update("""
                insert into extraction_quota(id, extraction_task_id, max_rows, max_bytes, timeout_seconds, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, "extraction-quota-" + UUID.randomUUID(), id,
                optionalLong(optionalMap(request, "quota"), "maxRows", 10_000),
                optionalLong(optionalMap(request, "quota"), "maxBytes", 1024 * 1024 * 100L),
                optionalLong(optionalMap(request, "quota"), "timeoutSeconds", 5),
                timestamp(now));
        auditAndOutbox(context, "extraction_task.create", "extraction_task", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create extraction task"),
                Map.of("templateId", templateId, "templateVersion", templateVersion));
        return getById("extraction_task", id);
    }

    @Transactional
    public Map<String, Object> transitionExtractionTask(String id, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "DATA_EXTRACT");
        Map<String, Object> current = getById("extraction_task", id);
        ExtractionTaskStatus expectedStatus = enumValue(request, "expectedStatus", ExtractionTaskStatus.class);
        ExtractionTaskStatus targetStatus = enumValue(request, "targetStatus", ExtractionTaskStatus.class);
        long expectedVersion = requiredLong(request, "expectedVersion");
        ExtractionTaskStatus currentStatus = ExtractionTaskStatus.valueOf(String.valueOf(current.get("status")));
        long currentVersion = ((Number) current.get("version")).longValue();
        assertExpected(currentStatus, expectedStatus, currentVersion, expectedVersion);
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw PlatformException.conflict("EXTRACTION_TASK_INVALID_TRANSITION",
                    "Cannot transition extraction task from " + currentStatus + " to " + targetStatus,
                    Map.of("id", id, "currentStatus", currentStatus.name(), "targetStatus", targetStatus.name()));
        }
        String fencingToken = requiredString(request, "fencingToken", null);
        fencingTokenService.consume(context, "extraction_task", id, fencingToken);
        long newVersion = currentVersion + 1;
        Instant now = clock.instant();
        jdbcTemplate.update("""
                update extraction_task
                   set status = ?, version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = ? and version = ?
                """, targetStatus.name(), newVersion, context.actor(), timestamp(now),
                id, expectedStatus.name(), expectedVersion);
        auditAndOutbox(context, "extraction_task.transition", "extraction_task", id, newVersion,
                hash(current), hash(Map.of("status", targetStatus.name(), "version", newVersion)),
                "SUCCESS", requiredString(request, "reason", null),
                Map.of("from", expectedStatus.name(), "to", targetStatus.name(),
                        "fencingToken", fencingToken));
        return getById("extraction_task", id);
    }

    @Transactional
    public Map<String, Object> createReplayExecution(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "REPLAY_EXECUTE");
        String replayPlanId = requiredString(request, "replayPlanId", null);
        Map<String, Object> replayPlan = getById("replay_plan", replayPlanId);
        if (!PlanStatus.APPROVED.name().equals(String.valueOf(replayPlan.get("status")))) {
            throw PlatformException.conflict("REPLAY_PLAN_NOT_APPROVED",
                    "流量回放计划必须审批通过后才能创建执行",
                    Map.of("replayPlanId", replayPlanId, "status", replayPlan.get("status")));
        }
        Instant now = clock.instant();
        String id = optionalString(request, "id", "replay-execution-" + UUID.randomUUID());
        jdbcTemplate.update("""
                insert into replay_execution(
                    id, replay_plan_id, status, version, executor_config_json, metrics_json,
                    created_by, created_at, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, replayPlanId, ReplayExecutionStatus.QUEUED.name(), 1L,
                jsonValue(request, "executorConfig", Map.of("qps", 1, "concurrency", 1)),
                json(Map.of()), context.actor(), timestamp(now), context.actor(), timestamp(now));
        auditAndOutbox(context, "replay_execution.create", "replay_execution", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create replay execution"),
                Map.of("replayPlanId", replayPlanId));
        return getById("replay_execution", id);
    }

    @Transactional
    public Map<String, Object> transitionReplayExecution(String id, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "REPLAY_EXECUTE");
        Map<String, Object> current = getById("replay_execution", id);
        ReplayExecutionStatus expectedStatus = enumValue(request, "expectedStatus", ReplayExecutionStatus.class);
        ReplayExecutionStatus targetStatus = enumValue(request, "targetStatus", ReplayExecutionStatus.class);
        long expectedVersion = requiredLong(request, "expectedVersion");
        ReplayExecutionStatus currentStatus = ReplayExecutionStatus.valueOf(String.valueOf(current.get("status")));
        long currentVersion = ((Number) current.get("version")).longValue();
        assertExpected(currentStatus, expectedStatus, currentVersion, expectedVersion);
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw PlatformException.conflict("REPLAY_EXECUTION_INVALID_TRANSITION",
                    "Cannot transition replay execution from " + currentStatus + " to " + targetStatus,
                    Map.of("id", id, "currentStatus", currentStatus.name(), "targetStatus", targetStatus.name()));
        }
        String fencingToken = requiredString(request, "fencingToken", null);
        fencingTokenService.consume(context, "replay_execution", id, fencingToken);
        long newVersion = currentVersion + 1;
        Instant now = clock.instant();
        jdbcTemplate.update("""
                update replay_execution
                   set status = ?, version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = ? and version = ?
                """, targetStatus.name(), newVersion, context.actor(), timestamp(now),
                id, expectedStatus.name(), expectedVersion);
        auditAndOutbox(context, "replay_execution.transition", "replay_execution", id, newVersion,
                hash(current), hash(Map.of("status", targetStatus.name(), "version", newVersion)),
                "SUCCESS", requiredString(request, "reason", null),
                Map.of("from", expectedStatus.name(), "to", targetStatus.name(),
                        "fencingToken", fencingToken));
        return getById("replay_execution", id);
    }

    @Transactional
    public Map<String, Object> createRecordingSession(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "RECORD_ARGUMENTS");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "rec-" + UUID.randomUUID());
        ApplicationEnvironment scope = requireApplicationEnvironment(request);
        long ttlSeconds = optionalLong(request, "ttlSeconds", 900);
        if (ttlSeconds < 1 || ttlSeconds > 7_200) {
            throw PlatformException.badRequest("INVALID_TTL", "ttlSeconds must be between 1 and 7200");
        }
        long maxEvents = optionalLong(request, "maxEvents", 10_000);
        if (maxEvents < 1 || maxEvents > 100_000) {
            throw PlatformException.badRequest("INVALID_MAX_EVENTS", "maxEvents must be between 1 and 100000");
        }
        jdbcTemplate.update("""
                insert into recording_session(
                    id, application_id, environment_id, status, version, max_events, ttl_seconds,
                    target_json, quota_json, created_by, created_at, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                scope.applicationId(),
                scope.environmentId(),
                RecordingSessionStatus.DRAFT.name(),
                1L,
                maxEvents,
                ttlSeconds,
                json(optionalMap(request, "target")),
                json(optionalMap(request, "quota")),
                context.actor(),
                timestamp(now),
                context.actor(),
                timestamp(now)
        );
        jdbcTemplate.update("""
                insert into recording_session_target(id, recording_session_id, protocol, target_json, created_at)
                values (?, ?, ?, ?, ?)
                """, "recording-session-target-" + UUID.randomUUID(), id,
                optionalString(optionalMap(request, "target"), "protocol", "JAVA_METHOD"),
                jsonValue(request, "target", Map.of()), timestamp(now));
        jdbcTemplate.update("""
                insert into recording_session_quota(id, recording_session_id, max_events, max_bytes, expires_at, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, "recording-session-quota-" + UUID.randomUUID(), id,
                maxEvents,
                optionalLong(optionalMap(request, "quota"), "maxBytes", 1024 * 1024 * 1024L),
                timestamp(now.plusSeconds(ttlSeconds)),
                timestamp(now));
        auditAndOutbox(context, "recording_session.create", "recording_session", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create recording session"),
                Map.of("status", RecordingSessionStatus.DRAFT.name()));
        return getById("recording_session", id);
    }

    @Transactional
    public Map<String, Object> transitionRecordingSession(String id, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "RECORD_ARGUMENTS");
        String reason = requiredString(request, "reason", null);
        String fencingToken = requiredString(request, "fencingToken", null);
        RecordingSessionStatus expectedStatus = enumValue(request, "expectedStatus", RecordingSessionStatus.class);
        RecordingSessionStatus targetStatus = enumValue(request, "targetStatus", RecordingSessionStatus.class);
        long expectedVersion = requiredLong(request, "expectedVersion");
        Map<String, Object> current = getById("recording_session", id);
        RecordingSessionStatus currentStatus = RecordingSessionStatus.valueOf(String.valueOf(current.get("status")));
        long currentVersion = ((Number) current.get("version")).longValue();
        assertExpected(currentStatus, expectedStatus, currentVersion, expectedVersion);
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw PlatformException.conflict("RECORDING_SESSION_INVALID_TRANSITION",
                    "Cannot transition recording session from " + currentStatus + " to " + targetStatus,
                    Map.of("id", id, "currentStatus", currentStatus.name(), "targetStatus", targetStatus.name()));
        }
        if (targetStatus == RecordingSessionStatus.APPROVED) {
            ensureSelfApproval("RECORDING_SESSION", id, currentVersion, current, context, reason,
                    "批准自己的录制申请");
            requireApprovedApproval("RECORDING_SESSION", id, currentVersion, current);
        }
        fencingTokenService.consume(context, "recording_session", id, fencingToken);
        long newVersion = currentVersion + 1;
        Instant now = clock.instant();
        jdbcTemplate.update("""
                update recording_session
                   set status = ?, version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = ? and version = ?
                """, targetStatus.name(), newVersion, context.actor(), timestamp(now),
                id, expectedStatus.name(), expectedVersion);
        Map<String, Object> updatedSession = getById("recording_session", id);
        if (targetStatus == RecordingSessionStatus.WAITING_APPROVAL) {
            ensureApprovalRecord("RECORDING_SESSION", id, newVersion, updatedSession,
                    context.actor(), reason, now);
        }
        auditAndOutbox(context, "recording_session.transition", "recording_session", id, newVersion,
                hash(current), hash(updatedSession),
                "SUCCESS", reason, Map.of("from", expectedStatus.name(), "to", targetStatus.name(), "fencingToken", fencingToken));
        return updatedSession;
    }

    @Transactional
    public Map<String, Object> createDatasetVersion(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "IMPORT_TO_TEST");
        String sourceSessionId = requiredString(request, "sourceSessionId", null);
        Map<String, Object> session = getById("recording_session", sourceSessionId);
        if (!RecordingSessionStatus.COMPLETED.name().equals(String.valueOf(session.get("status")))) {
            throw PlatformException.conflict("SOURCE_SESSION_NOT_COMPLETED",
                    "Dataset can only be created from a completed recording session",
                    Map.of("sourceSessionId", sourceSessionId, "status", session.get("status")));
        }
        String datasetId = requiredString(request, "datasetId", "dataset-" + UUID.randomUUID());
        ensureDataset(datasetId, request, context);
        long version = nextDatasetVersion(datasetId);
        String id = datasetId + ":" + version;
        List<Object> objectReferences = new ArrayList<>(optionalList(request, "objectReferences"));
        List<Map<String, Object>> recordingBatches = normalizeRows(jdbcTemplate.queryForList("""
                select batch.id, batch.object_uri, batch.event_count, batch.bytes_count,
                       payload.content_hash, payload.metadata_json
                  from recording_batch batch
                  left join payload_object payload on payload.object_uri = batch.object_uri
                 where batch.recording_session_id = ? and batch.status = 'SEALED'
                 order by batch.created_at, batch.id
                """, sourceSessionId));
        if (objectReferences.isEmpty()) {
            recordingBatches.forEach(batch -> {
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("objectType", "JSONL_ENCRYPTED");
                reference.put("objectUri", batch.get("object_uri"));
                reference.put("contentHash", batch.get("content_hash") == null
                        ? hash(Map.of("batchId", batch.get("id"), "objectUri", batch.get("object_uri")))
                        : batch.get("content_hash"));
                reference.put("bytesCount", batch.get("bytes_count"));
                reference.put("metadata", PlatformJson.readMap(
                        String.valueOf(batch.getOrDefault("metadata_json", "{}"))));
                objectReferences.add(reference);
            });
        }
        Map<String, Object> schema = request.containsKey("schema")
                ? optionalMap(request, "schema")
                : Map.of(
                        "format", "application/x-ndjson",
                        "eventModel", "runtime-mock.recording-event.v1"
                );
        Map<String, Object> manifest = request.containsKey("manifest")
                ? optionalMap(request, "manifest")
                : Map.of(
                        "sourceSessionId", sourceSessionId,
                        "batchCount", recordingBatches.size(),
                        "eventCount", recordingBatches.stream()
                                .mapToLong(batch -> ((Number) batch.get("event_count")).longValue())
                                .sum(),
                        "objects", objectReferences
                );
        String schemaHash = optionalString(request, "schemaHash", hash(schema));
        String manifestHash = optionalString(request, "manifestHash", hash(manifest));
        String maskingHash = optionalString(request, "maskingHash", hash("default-sensitive-fields-v1"));
        jdbcTemplate.update("""
                insert into dataset_version(
                    id, dataset_id, version, source_session_id, schema_hash, manifest_hash, masking_hash,
                    retention_policy, object_references_json, created_by, created_at, source_type, source_ref
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                datasetId,
                version,
                sourceSessionId,
                schemaHash,
                manifestHash,
                maskingHash,
                requiredString(request, "retentionPolicy", "P30D"),
                json(objectReferences),
                context.actor(),
                timestamp(clock.instant()),
                "RECORDING_SESSION",
                sourceSessionId
        );
        Instant now = clock.instant();
        jdbcTemplate.update("""
                insert into dataset_source_session(id, dataset_version_id, recording_session_id, created_at)
                values (?, ?, ?, ?)
                """, "dataset-source-session-" + UUID.randomUUID(), id, sourceSessionId, timestamp(now));
        jdbcTemplate.update("""
                insert into dataset_schema(id, dataset_version_id, schema_hash, schema_json, created_at)
                values (?, ?, ?, ?, ?)
                """, "dataset-schema-" + UUID.randomUUID(), id,
                schemaHash, json(schema), timestamp(now));
        jdbcTemplate.update("""
                insert into dataset_manifest(id, dataset_version_id, manifest_hash, manifest_json, created_at)
                values (?, ?, ?, ?, ?)
                """, "dataset-manifest-" + UUID.randomUUID(), id,
                manifestHash, json(manifest), timestamp(now));
        for (Object item : objectReferences) {
            Map<String, Object> objectRef = asMap(item, "objectReferences");
            jdbcTemplate.update("""
                    insert into dataset_object_reference(
                        id, dataset_version_id, object_type, object_uri, content_hash, bytes_count,
                        created_at, metadata_json
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, "dataset-object-" + UUID.randomUUID(), id,
                    requiredString(objectRef, "objectType", "JSONL"),
                    requiredString(objectRef, "objectUri", null),
                    requiredString(objectRef, "contentHash", manifestHash),
                    optionalLong(objectRef, "bytesCount", 0),
                    timestamp(now),
                    jsonValue(objectRef, "metadata", Map.of()));
        }
        auditAndOutbox(context, "dataset_version.create", "dataset_version", id, version,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create dataset version"),
                Map.of("datasetId", datasetId, "sourceSessionId", sourceSessionId));
        return getById("dataset_version", id);
    }

    @Transactional
    public Map<String, Object> createReplayPlan(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "IMPORT_TO_TEST");
        String datasetId = requiredString(request, "datasetId", null);
        long datasetVersion = requiredLong(request, "datasetVersion");
        getDatasetVersion(datasetId, datasetVersion);
        Instant now = clock.instant();
        String id = optionalString(request, "id", "replay-" + UUID.randomUUID());
        jdbcTemplate.update("""
                insert into replay_plan(
                    id, version, dataset_id, dataset_version, target_environment, target_application, status,
                    side_effect_policy_hash, comparison_policy_hash, execution_policy_json,
                    created_by, created_at, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                1L,
                datasetId,
                datasetVersion,
                requiredString(request, "targetEnvironment", null),
                requiredString(request, "targetApplication", null),
                PlanStatus.DRAFT.name(),
                requiredString(request, "sideEffectPolicyHash", null),
                requiredString(request, "comparisonPolicyHash", null),
                json(optionalMap(request, "executionPolicy")),
                context.actor(),
                timestamp(now),
                context.actor(),
                timestamp(now)
        );
        insertReplayPlanMetadata(context, id, 1L, request, now);
        auditAndOutbox(context, "replay_plan.create", "replay_plan", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create replay plan"),
                Map.of("datasetId", datasetId, "datasetVersion", datasetVersion));
        return getById("replay_plan", id);
    }

    @Transactional
    public Map<String, Object> transitionReplayPlan(String id, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "IMPORT_TO_TEST");
        String reason = requiredString(request, "reason", null);
        String fencingToken = requiredString(request, "fencingToken", null);
        PlanStatus expectedStatus = enumValue(request, "expectedStatus", PlanStatus.class);
        PlanStatus targetStatus = enumValue(request, "targetStatus", PlanStatus.class);
        long expectedVersion = requiredLong(request, "expectedVersion");
        Map<String, Object> current = getById("replay_plan", id);
        PlanStatus currentStatus = PlanStatus.valueOf(String.valueOf(current.get("status")));
        long currentVersion = ((Number) current.get("version")).longValue();
        assertExpected(currentStatus, expectedStatus, currentVersion, expectedVersion);
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw PlatformException.conflict("REPLAY_PLAN_INVALID_TRANSITION",
                    "Cannot transition replay plan from " + currentStatus + " to " + targetStatus,
                    Map.of("id", id, "currentStatus", currentStatus.name(), "targetStatus", targetStatus.name()));
        }
        if (targetStatus == PlanStatus.APPROVED) {
            ensureSelfApproval("REPLAY_PLAN", id, currentVersion, current, context, reason,
                    "批准自己的回放申请");
            requireApprovedApproval("REPLAY_PLAN", id, currentVersion, current);
        }
        fencingTokenService.consume(context, "replay_plan", id, fencingToken);
        long newVersion = currentVersion + 1;
        Instant now = clock.instant();
        jdbcTemplate.update("""
                update replay_plan
                   set status = ?, version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = ? and version = ?
                """, targetStatus.name(), newVersion, context.actor(), timestamp(now),
                id, expectedStatus.name(), expectedVersion);
        Map<String, Object> updatedPlan = getById("replay_plan", id);
        if (targetStatus == PlanStatus.WAITING_APPROVAL) {
            ensureApprovalRecord("REPLAY_PLAN", id, newVersion, updatedPlan,
                    context.actor(), reason, now);
        }
        auditAndOutbox(context, "replay_plan.transition", "replay_plan", id, newVersion,
                hash(current), hash(updatedPlan),
                "SUCCESS", reason, Map.of("from", expectedStatus.name(), "to", targetStatus.name(), "fencingToken", fencingToken));
        return updatedPlan;
    }

    @Transactional
    public Map<String, Object> createApproval(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "APPROVE");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "approval-" + UUID.randomUUID());
        String subjectType = requiredString(request, "subjectType", null);
        String subjectId = requiredString(request, "subjectId", null);
        long subjectVersion = requiredLong(request, "subjectVersion");
        Map<String, Object> subject = approvalSubject(subjectType, subjectId, subjectVersion);
        String subjectHash = hash(subject);
        String providedHash = optionalString(request, "subjectHash", null);
        if (providedHash != null && !providedHash.equals(subjectHash)) {
            throw PlatformException.conflict("APPROVAL_SUBJECT_HASH_MISMATCH",
                    "Provided subject hash does not match the current immutable approval subject",
                    Map.of("subjectType", subjectType, "subjectId", subjectId,
                            "subjectVersion", subjectVersion));
        }
        jdbcTemplate.update("""
                insert into approval_request(
                    id, subject_type, subject_id, subject_version, subject_hash, status,
                    requester, reason, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                subjectType,
                subjectId,
                subjectVersion,
                subjectHash,
                ApprovalStatus.WAITING_APPROVAL.name(),
                context.actor(),
                requiredString(request, "reason", null),
                timestamp(now),
                timestamp(now)
        );
        List<?> approvers = optionalList(request, "approvers");
        if (approvers.isEmpty()) {
            approvers = List.of(context.actor());
        }
        int order = 1;
        for (Object approver : approvers) {
            jdbcTemplate.update("""
                    insert into approval_step(id, approval_id, step_order, approver, status)
                    values (?, ?, ?, ?, ?)
                    """, "approval-step-" + UUID.randomUUID(), id, order++, String.valueOf(approver),
                    ApprovalStatus.WAITING_APPROVAL.name());
        }
        auditAndOutbox(context, "approval_request.create", "approval_request", id, 1,
                "", hash(request), "SUCCESS", requiredString(request, "reason", null),
                Map.of("approverCount", approvers.size()));
        return getById("approval_request", id);
    }

    private void requireApprovedApproval(String subjectType, String subjectId, long subjectVersion,
                                         Map<String, Object> subject) {
        List<Map<String, Object>> approvals = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from approval_request
                 where subject_type = ?
                   and subject_id = ?
                   and subject_version = ?
                   and status = 'APPROVED'
                 order by updated_at desc
                """, subjectType, subjectId, subjectVersion));
        String currentHash = hash(subject);
        boolean valid = approvals.stream()
                .anyMatch(approval -> currentHash.equals(String.valueOf(approval.get("subject_hash"))));
        if (!valid) {
            throw PlatformException.conflict("APPROVAL_REQUIRED",
                    "当前资源版本必须先获得审批通过",
                    Map.of("subjectType", subjectType, "subjectId", subjectId,
                            "subjectVersion", subjectVersion));
        }
    }

    private Map<String, Object> approvalSubject(String subjectType, String subjectId, long subjectVersion) {
        return switch (subjectType) {
            case "OPERATION_PLAN" -> versionedSubject("operation_plan", subjectId, subjectVersion);
            case "RECORDING_SESSION" -> versionedSubject("recording_session", subjectId, subjectVersion);
            case "REPLAY_PLAN" -> versionedSubject("replay_plan", subjectId, subjectVersion);
            case "REPLAY_EXECUTION" -> versionedSubject("replay_execution", subjectId, subjectVersion);
            case "EXTRACTION_TASK" -> versionedSubject("extraction_task", subjectId, subjectVersion);
            case "RULE" -> normalizeRow(jdbcTemplate.queryForMap(
                    "select * from rule_version where rule_id = ? and version = ?", subjectId, subjectVersion));
            case "RECORDING_RULE" -> normalizeRow(jdbcTemplate.queryForMap(
                    "select * from recording_rule_version where recording_rule_id = ? and version = ?",
                    subjectId, subjectVersion));
            case "DATASET_VERSION" -> normalizeRow(jdbcTemplate.queryForMap(
                    "select * from dataset_version where dataset_id = ? and version = ?", subjectId, subjectVersion));
            default -> throw PlatformException.badRequest(
                    "INVALID_APPROVAL_SUBJECT", "Unsupported approval subject type: " + subjectType);
        };
    }

    private Map<String, Object> versionedSubject(String table, String subjectId, long subjectVersion) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from " + table + " where id = ? and version = ?", subjectId, subjectVersion);
        if (rows.isEmpty()) {
            throw PlatformException.notFound(table, subjectId + ":" + subjectVersion);
        }
        return normalizeRow(rows.get(0));
    }

    @Transactional
    public Map<String, Object> decideApproval(String id, RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "APPROVE");
        Map<String, Object> approval = getById("approval_request", id);
        if (!ApprovalStatus.WAITING_APPROVAL.name().equals(String.valueOf(approval.get("status")))) {
            throw PlatformException.conflict("APPROVAL_ALREADY_DECIDED",
                    "该审批申请已完成处理，不能重复审批",
                    Map.of("approvalId", id, "status", approval.get("status")));
        }
        String decision = requiredString(request, "decision", null);
        if (!ApprovalStatus.APPROVED.name().equals(decision)
                && !ApprovalStatus.REJECTED.name().equals(decision)) {
            throw PlatformException.badRequest("INVALID_DECISION", "审批决定只能是“通过”或“拒绝”");
        }
        String reason = requiredString(request, "reason", null);
        List<Map<String, Object>> steps = jdbcTemplate.queryForList("""
                select * from approval_step
                 where approval_id = ? and approver = ? and status = ?
                 order by step_order
                 limit 1
                """, id, context.actor(), ApprovalStatus.WAITING_APPROVAL.name());
        if (steps.isEmpty()) {
            throw PlatformException.forbidden("APPROVAL_ASSIGNEE");
        }
        Map<String, Object> step = normalizeRow(steps.get(0));
        Instant now = clock.instant();
        jdbcTemplate.update("update approval_step set status = ?, decided_at = ? where id = ?",
                decision, timestamp(now), step.get("id"));
        jdbcTemplate.update("""
                insert into approval_decision(id, approval_id, step_id, actor, decision, reason, decided_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, "approval-decision-" + UUID.randomUUID(), id, step.get("id"), context.actor(),
                decision, reason, timestamp(now));
        String newStatus = "APPROVED".equals(decision) && remainingApprovalSteps(id) == 0
                ? ApprovalStatus.APPROVED.name()
                : "REJECTED".equals(decision) ? ApprovalStatus.REJECTED.name() : ApprovalStatus.WAITING_APPROVAL.name();
        jdbcTemplate.update("update approval_request set status = ?, updated_at = ? where id = ?",
                newStatus, timestamp(now), id);
        auditAndOutbox(context, "approval_request.decide", "approval_request", id, 1,
                hash(approval), hash(Map.of("status", newStatus, "decision", decision)),
                "SUCCESS", reason, Map.of("decision", decision, "status", newStatus));
        return getById("approval_request", id);
    }

    public List<Map<String, Object>> audits() {
        return normalizeRows(jdbcTemplate.queryForList("select * from audit_record order by sequence"));
    }

    public List<Map<String, Object>> outbox() {
        return normalizeRows(jdbcTemplate.queryForList("select * from outbox_event order by created_at, id"));
    }

    public void recordEvent(RequestContext context, String action, String resourceType, String resourceId,
                            long resourceVersion, Object before, Object after, String result,
                            String reason, Map<String, Object> details) {
        auditAndOutbox(context, action, resourceType, resourceId, resourceVersion,
                hash(before == null ? "" : before), hash(after == null ? "" : after),
                result, reason, details);
    }

    public String stableHash(Object value) {
        return hash(value == null ? "" : value);
    }

    private void insertLabels(String instanceId, Map<String, Object> labels, Instant now) {
        labels.forEach((key, value) -> jdbcTemplate.update("""
                insert into instance_label(id, instance_id, label_key, label_value, created_at)
                values (?, ?, ?, ?, ?)
                """, "instance-label-" + UUID.randomUUID(), instanceId, key, String.valueOf(value), timestamp(now)));
    }

    private void insertAgentCapabilities(String agentId, List<?> capabilities, Instant now) {
        for (Object capability : capabilities) {
            String capabilityName = String.valueOf(capability);
            jdbcTemplate.update("""
                    insert into agent_capability(id, agent_id, capability, metadata_json, created_at)
                    values (?, ?, ?, ?, ?)
                    """, "agent-capability-" + UUID.randomUUID(), agentId, capabilityName,
                    json(Map.of()), timestamp(now));
        }
    }

    private void insertRuleVersion(RequestContext context, String ruleId, long version,
                                   Map<String, Object> request, Instant now) {
        String versionId = ruleId + ":" + version;
        Object script = request.getOrDefault("script", Map.of());
        jdbcTemplate.update("""
                insert into rule_version(
                    id, rule_id, version, status, risk_level, matcher_json, script_hash,
                    script_json, governance_json, created_by, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                ruleId,
                version,
                requiredString(request, "versionStatus", "DRAFT"),
                requiredString(request, "riskLevel", "MEDIUM"),
                jsonValue(request, "matcher", Map.of()),
                optionalString(request, "scriptHash", hash(script)),
                json(script),
                jsonValue(request, "governance", Map.of("ttlSeconds", 3600, "maxHits", 10_000)),
                context.actor(),
                timestamp(now));
        List<?> targets = optionalList(request, "targets");
        if (targets.isEmpty() && request.containsKey("target")) {
            targets = List.of(request.get("target"));
        }
        for (Object item : targets) {
            Map<String, Object> target = asMap(item, "targets");
            jdbcTemplate.update("""
                    insert into rule_target(
                        id, rule_version_id, protocol, class_name, method_name, matcher_json, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    "rule-target-" + UUID.randomUUID(),
                    versionId,
                    requiredString(target, "protocol", "JAVA_METHOD"),
                    requiredString(target, "className", null),
                    requiredString(target, "methodName", null),
                    jsonValue(target, "matcher", Map.of()),
                    timestamp(now));
        }
        for (Object capability : optionalList(request, "capabilities")) {
            jdbcTemplate.update("""
                    insert into rule_capability(id, rule_version_id, capability, created_at)
                    values (?, ?, ?, ?)
                    """, "rule-capability-" + UUID.randomUUID(), versionId, String.valueOf(capability), timestamp(now));
        }
    }

    private void insertRecordingRuleVersion(RequestContext context, String ruleId, long version,
                                            Map<String, Object> request, Instant now) {
        String versionId = ruleId + ":" + version;
        String versionStatus = requiredString(request, "versionStatus", "DRAFT");
        String targetJson = jsonValue(request, "target", Map.of());
        if ("ACTIVE".equals(versionStatus)) {
            Integer activeCount = jdbcTemplate.queryForObject("""
                    select count(*)
                      from recording_rule_version
                     where status = 'ACTIVE' and target_json = ?
                    """, Integer.class, targetJson);
            if (activeCount != null && activeCount >= 10) {
                throw PlatformException.conflict("RECORDING_RULE_METHOD_LIMIT",
                        "At most 10 active recording rules may target the same method",
                        Map.of("target", targetJson, "activeCount", activeCount));
            }
        }
        jdbcTemplate.update("""
                insert into recording_rule_version(
                    id, recording_rule_id, version, status, protocol, target_json, sampling_json,
                    quota_json, masking_policy_id, created_by, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                ruleId,
                version,
                versionStatus,
                requiredString(request, "protocol", "JAVA_METHOD"),
                targetJson,
                validatedSampling(request),
                jsonValue(request, "quota", Map.of("maxEvents", 10_000, "maxBytes", 1024 * 1024 * 1024L)),
                optionalString(request, "maskingPolicyId", null),
                context.actor(),
                timestamp(now));
    }

    private void insertExtractionTemplateVersion(RequestContext context, String templateId, long version,
                                                 Map<String, Object> request, Instant now) {
        String versionId = templateId + ":" + version;
        Object template = request.getOrDefault("template", Map.of());
        jdbcTemplate.update("""
                insert into extraction_template_version(
                    id, template_id, version, status, root_table, template_hash, template_json,
                    quota_json, created_by, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                templateId,
                version,
                requiredString(request, "versionStatus", "DRAFT"),
                requiredString(request, "rootTable", null),
                optionalString(request, "templateHash", hash(template)),
                json(template),
                jsonValue(request, "quota", Map.of("maxRows", 10_000, "timeoutSeconds", 5)),
                context.actor(),
                timestamp(now));
        for (Object item : optionalList(request, "relations")) {
            Map<String, Object> relation = asMap(item, "relations");
            jdbcTemplate.update("""
                    insert into extraction_relation(
                        id, template_version_id, source_table, target_table, relation_json, created_at
                    ) values (?, ?, ?, ?, ?, ?)
                    """, "extraction-relation-" + UUID.randomUUID(), versionId,
                    requiredString(relation, "sourceTable", null),
                    requiredString(relation, "targetTable", null),
                    jsonValue(relation, "relation", Map.of()),
                    timestamp(now));
        }
    }

    private void insertReplayPlanMetadata(RequestContext context, String replayPlanId, long version,
                                          Map<String, Object> request, Instant now) {
        Object plan = request.getOrDefault("plan", request);
        jdbcTemplate.update("""
                insert into replay_plan_version(
                    id, replay_plan_id, version, status, plan_hash, plan_json, created_by, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                replayPlanId + ":" + version,
                replayPlanId,
                version,
                requiredString(request, "versionStatus", "DRAFT"),
                optionalString(request, "planHash", hash(plan)),
                json(plan),
                context.actor(),
                timestamp(now));
        jdbcTemplate.update("""
                insert into replay_side_effect_policy(id, replay_plan_id, policy_hash, policy_json, created_at)
                values (?, ?, ?, ?, ?)
                """, "replay-side-effect-policy-" + UUID.randomUUID(), replayPlanId,
                requiredString(request, "sideEffectPolicyHash", null),
                jsonValue(request, "sideEffectPolicy", Map.of()),
                timestamp(now));
        jdbcTemplate.update("""
                insert into comparison_policy(id, replay_plan_id, policy_hash, policy_json, created_at)
                values (?, ?, ?, ?, ?)
                """, "comparison-policy-" + UUID.randomUUID(), replayPlanId,
                requiredString(request, "comparisonPolicyHash", null),
                jsonValue(request, "comparisonPolicy", Map.of()),
                timestamp(now));
        for (Object item : optionalList(request, "targets")) {
            Map<String, Object> target = asMap(item, "targets");
            jdbcTemplate.update("""
                    insert into replay_target(id, replay_plan_id, target_type, target_json, created_at)
                    values (?, ?, ?, ?, ?)
                    """, "replay-target-" + UUID.randomUUID(), replayPlanId,
                    requiredString(target, "targetType", "JAVA_METHOD"),
                    json(target),
                    timestamp(now));
        }
    }

    private Map<String, Object> ensureSelfApprovalForOperation(String operationId,
                                                               Map<String, Object> operation,
                                                               RequestContext context,
                                                               String reason) {
        String approvalId = optionalString(operation, "approval_id", null);
        if (approvalId == null) {
            approvalId = "approval-" + UUID.randomUUID();
            jdbcTemplate.update("update operation_plan set approval_id = ? where id = ?",
                    approvalId, operationId);
            operation = getById("operation_plan", operationId);
            createApprovalRecord(approvalId, "OPERATION_PLAN", operationId,
                    ((Number) operation.get("version")).longValue(), operation,
                    context.actor(), reason, clock.instant());
        }
        Map<String, Object> approval = getById("approval_request", approvalId);
        if (ApprovalStatus.WAITING_APPROVAL.name().equals(String.valueOf(approval.get("status")))) {
            decideApproval(approvalId, context, Map.of(
                    "decision", ApprovalStatus.APPROVED.name(),
                    "reason", "批准自己的发布申请"));
        }
        return getById("operation_plan", operationId);
    }

    private void ensureSelfApproval(String subjectType, String subjectId, long subjectVersion,
                                    Map<String, Object> subject, RequestContext context,
                                    String reason, String decisionReason) {
        Map<String, Object> approval = ensureApprovalRecord(
                subjectType, subjectId, subjectVersion, subject,
                context.actor(), reason, clock.instant());
        if (ApprovalStatus.WAITING_APPROVAL.name().equals(String.valueOf(approval.get("status")))) {
            decideApproval(String.valueOf(approval.get("id")), context, Map.of(
                    "decision", ApprovalStatus.APPROVED.name(),
                    "reason", decisionReason));
        }
    }

    private Map<String, Object> ensureApprovalRecord(String subjectType, String subjectId,
                                                     long subjectVersion, Map<String, Object> subject,
                                                     String requester, String reason, Instant now) {
        List<Map<String, Object>> approvals = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from approval_request
                 where subject_type = ?
                   and subject_id = ?
                   and subject_version = ?
                   and status in ('WAITING_APPROVAL', 'APPROVED')
                 order by updated_at desc
                 limit 1
                """, subjectType, subjectId, subjectVersion));
        if (!approvals.isEmpty()) {
            return approvals.getFirst();
        }
        String approvalId = "approval-" + UUID.randomUUID();
        createApprovalRecord(approvalId, subjectType, subjectId, subjectVersion,
                subject, requester, reason, now);
        return getById("approval_request", approvalId);
    }

    private void createApprovalRecord(String approvalId, String subjectType, String subjectId,
                                      long subjectVersion, Map<String, Object> subject,
                                      String requester, String reason, Instant now) {
        jdbcTemplate.update("""
                insert into approval_request(
                    id, subject_type, subject_id, subject_version, subject_hash, status,
                    requester, reason, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, approvalId, subjectType, subjectId, subjectVersion, hash(subject),
                ApprovalStatus.WAITING_APPROVAL.name(), requester, reason,
                timestamp(now), timestamp(now));
        jdbcTemplate.update("""
                insert into approval_step(id, approval_id, step_order, approver, status)
                values (?, ?, 1, ?, ?)
                """, "approval-step-" + UUID.randomUUID(), approvalId, requester,
                ApprovalStatus.WAITING_APPROVAL.name());
    }

    private void approveRuleVersionForOperation(Map<String, Object> operation,
                                                RequestContext context, Instant now) {
        if (!"rule".equals(String.valueOf(operation.get("resource_type")))) {
            return;
        }
        String ruleId = String.valueOf(operation.get("resource_id"));
        long version = ((Number) operation.get("resource_version")).longValue();
        jdbcTemplate.update("""
                update rule_version
                   set status = 'APPROVED'
                 where rule_id = ? and version = ? and status = 'DRAFT'
                """, ruleId, version);
        jdbcTemplate.update("""
                update rule
                   set status = 'APPROVED', updated_by = ?, updated_at = ?
                 where id = ?
                """, context.actor(), timestamp(now), ruleId);
    }

    private void validateRolloutResource(String applicationId, String environmentId,
                                         String resourceType, String resourceId,
                                         long resourceVersion) {
        if (!"rule".equals(resourceType)) {
            throw PlatformException.badRequest("INVALID_RESOURCE_TYPE",
                    "当前发布计划仅支持规则资源");
        }
        List<Map<String, Object>> rules = normalizeRows(jdbcTemplate.queryForList("""
                select * from rule
                 where id = ? and application_id = ? and environment_id = ?
                """, resourceId, applicationId, environmentId));
        if (rules.isEmpty()) {
            throw PlatformException.badRequest("INVALID_ROLLOUT_RESOURCE",
                    "所选规则不属于当前应用和环境");
        }
        Integer versionCount = jdbcTemplate.queryForObject("""
                select count(*) from rule_version where rule_id = ? and version = ?
                """, Integer.class, resourceId, resourceVersion);
        if (versionCount == null || versionCount == 0) {
            throw PlatformException.badRequest("INVALID_ROLLOUT_VERSION",
                    "所选规则版本不存在");
        }
    }

    private long nextScopedVersion(String table, String keyColumn, String keyValue) {
        Long maximum = jdbcTemplate.queryForObject(
                "select coalesce(max(version), 0) from " + table + " where " + keyColumn + " = ?",
                Long.class, keyValue);
        return nextCounter(table + ":" + keyValue, maximum == null ? 1 : maximum + 1);
    }

    private void ensureDataset(String datasetId, Map<String, Object> request, RequestContext context) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from dataset where id = ?", Integer.class, datasetId);
        if (count != null && count > 0) {
            return;
        }
        ApplicationEnvironment scope = requireApplicationEnvironment(request);
        try {
            jdbcTemplate.update("""
                    insert into dataset(id, name, application_id, environment_id, created_by, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, datasetId, optionalString(request, "name", datasetId),
                    scope.applicationId(),
                    scope.environmentId(),
                    context.actor(), timestamp(clock.instant()));
        } catch (DuplicateKeyException ignored) {
            // created concurrently
        }
    }

    private long nextDatasetVersion(String datasetId) {
        Long maximum = jdbcTemplate.queryForObject(
                "select coalesce(max(version), 0) from dataset_version where dataset_id = ?",
                Long.class, datasetId);
        return nextCounter("dataset_version:" + datasetId, maximum == null ? 1 : maximum + 1);
    }

    private long nextCounter(String counterKey, long initialValue) {
        int updated = jdbcTemplate.update("""
                update scoped_counter
                   set current_value = current_value + 1, updated_at = ?
                 where counter_key = ?
                """, timestamp(clock.instant()), counterKey);
        if (updated == 0) {
            try {
                jdbcTemplate.update("""
                        insert into scoped_counter(counter_key, current_value, updated_at)
                        values (?, ?, ?)
                        """, counterKey, initialValue, timestamp(clock.instant()));
                return initialValue;
            } catch (DuplicateKeyException ignored) {
                jdbcTemplate.update("""
                        update scoped_counter
                           set current_value = current_value + 1, updated_at = ?
                         where counter_key = ?
                        """, timestamp(clock.instant()), counterKey);
            }
        }
        Long value = jdbcTemplate.queryForObject(
                "select current_value from scoped_counter where counter_key = ?", Long.class, counterKey);
        if (value == null) {
            throw new IllegalStateException("Counter did not return a value: " + counterKey);
        }
        return value;
    }

    private Map<String, Object> getDatasetVersion(String datasetId, long version) {
        return normalizeRow(jdbcTemplate.queryForMap(
                "select * from dataset_version where dataset_id = ? and version = ?", datasetId, version));
    }

    private Map<String, Object> getExtractionTemplateVersion(String templateId, long version) {
        return normalizeRow(jdbcTemplate.queryForMap(
                "select * from extraction_template_version where template_id = ? and version = ?", templateId, version));
    }

    private Map<String, Object> getById(String table, String id) {
        try {
            return normalizeRow(jdbcTemplate.queryForMap("select * from " + table + " where id = ?", id));
        } catch (Exception e) {
            throw PlatformException.notFound(table, id);
        }
    }

    private void requireExists(String table, String id) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw PlatformException.notFound(table, id);
        }
    }

    private void validateEnvironment(String applicationId, String environmentId) {
        List<Map<String, Object>> rows = normalizeRows(jdbcTemplate.queryForList("""
                select * from environment where id = ? and application_id = ?
                """, environmentId, applicationId));
        if (rows.isEmpty()) {
            throw PlatformException.badRequest("INVALID_ENVIRONMENT",
                    "所选环境不存在，或不属于当前应用");
        }
        String type = String.valueOf(rows.get(0).get("type")).toUpperCase(Locale.ROOT);
        if (!List.of("DEV", "SIT", "UAT", "PROD").contains(type)) {
            throw PlatformException.badRequest("INVALID_ENVIRONMENT_TYPE",
                    "环境类型仅允许 DEV、SIT、UAT、PROD");
        }
    }

    private ApplicationEnvironment requireApplicationEnvironment(Map<String, Object> request) {
        String applicationId = requiredString(request, "applicationId", null);
        String environmentId = requiredString(request, "environmentId", null);
        requireExists("application", applicationId);
        validateEnvironment(applicationId, environmentId);
        return new ApplicationEnvironment(applicationId, environmentId);
    }

    private record ApplicationEnvironment(String applicationId, String environmentId) {
    }

    private record RegistrationApplication(String applicationId, String projectName,
                                           String applicationName) {
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizeRow).toList();
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }

    private Map<String, Object> safeAgent(Map<String, Object> agent) {
        Map<String, Object> safe = new LinkedHashMap<>(agent);
        safe.remove("token_hash");
        return safe;
    }

    private Map<String, Object> safeDatasource(Map<String, Object> datasource) {
        Map<String, Object> safe = new LinkedHashMap<>(datasource);
        safe.remove("config_json");
        return safe;
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }

    private long countWhere(String table, String where) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table + " where " + where, Long.class);
        return value == null ? 0 : value;
    }

    private int remainingApprovalSteps(String approvalId) {
        Integer value = jdbcTemplate.queryForObject("""
                select count(*) from approval_step where approval_id = ? and status = ?
                """, Integer.class, approvalId, ApprovalStatus.WAITING_APPROVAL.name());
        return value == null ? 0 : value;
    }

    private void assertExpected(Enum<?> currentStatus, Enum<?> expectedStatus, long currentVersion, long expectedVersion) {
        if (currentStatus != expectedStatus || currentVersion != expectedVersion) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "Resource status or version has changed",
                    Map.of("currentStatus", currentStatus.name(), "expectedStatus", expectedStatus.name(),
                            "currentVersion", currentVersion, "expectedVersion", expectedVersion));
        }
    }

    private String fencingCapability(String resourceType) {
        return switch (resourceType) {
            case "operation_plan", "rollout_batch", "rollout_instance_execution" -> "ROLLOUT_MANAGE";
            case "recording_session", "recording_rule" -> "RECORD_ARGUMENTS";
            case "replay_plan", "dataset_version" -> "IMPORT_TO_TEST";
            case "extraction_task", "extraction_template" -> "DATA_EXTRACT";
            case "replay_execution" -> "REPLAY_EXECUTE";
            case "rule", "rule_version" -> "RULE_MANAGE";
            case "agent_instance", "sidecar_instance" -> "AGENT_MANAGE";
            default -> "ADMIN";
        };
    }

    private void auditAndOutbox(RequestContext context, String action, String resourceType, String resourceId,
                                long resourceVersion, String beforeHash, String afterHash, String result,
                                String reason, Map<String, Object> details) {
        lockAuditChain();
        Instant now = clock.instant();
        String previousHash = previousAuditHash();
        String auditId = "audit-" + UUID.randomUUID();
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("id", auditId);
        auditPayload.put("occurredAt", now.toString());
        auditPayload.put("actor", context.actor());
        auditPayload.put("identitySource", context.identitySource());
        auditPayload.put("action", action);
        auditPayload.put("resourceType", resourceType);
        auditPayload.put("resourceId", resourceId);
        auditPayload.put("resourceVersion", resourceVersion);
        auditPayload.put("beforeHash", beforeHash);
        auditPayload.put("afterHash", afterHash);
        auditPayload.put("previousRecordHash", previousHash);
        auditPayload.put("correlationId", context.correlationId());
        auditPayload.put("ipAddress", context.ipAddress());
        auditPayload.put("device", context.device());
        auditPayload.put("result", result);
        auditPayload.put("reason", reason);
        auditPayload.put("details", details);
        String recordHash = hash(auditPayload);

        jdbcTemplate.update("""
                insert into audit_record(
                    id, occurred_at, actor, identity_source, action, resource_type, resource_id, resource_version,
                    before_hash, after_hash, previous_record_hash, record_hash, correlation_id, ip_address,
                    device, result, reason, details_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, auditId, timestamp(now), context.actor(), context.identitySource(), action,
                resourceType, resourceId, resourceVersion, beforeHash, afterHash, previousHash, recordHash,
                context.correlationId(), context.ipAddress(), context.device(), result, reason, json(details));

        jdbcTemplate.update("""
                insert into outbox_event(
                    id, aggregate_type, aggregate_id, event_type, payload_json, status, available_at, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """, "outbox-" + UUID.randomUUID(), resourceType, resourceId, action,
                json(auditPayload), "NEW", timestamp(now), timestamp(now));
    }

    private void lockAuditChain() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            String database = connection.getMetaData().getDatabaseProductName();
            if ("PostgreSQL".equalsIgnoreCase(database)) {
                try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                    statement.setLong(1, AUDIT_CHAIN_LOCK_ID);
                    statement.execute();
                }
            }
            return null;
        });
    }

    private String previousAuditHash() {
        List<String> hashes = jdbcTemplate.query(
                "select record_hash from audit_record order by sequence desc limit 1",
                (rs, rowNum) -> rs.getString(1));
        return hashes.isEmpty() ? GENESIS_HASH : hashes.get(0);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize JSON", e);
        }
    }

    private String jsonValue(Map<String, Object> request, String key, Object defaultValue) {
        Object value = request.containsKey(key) ? request.get(key) : defaultValue;
        return json(value == null ? defaultValue : value);
    }

    private String validatedSampling(Map<String, Object> request) {
        Map<String, Object> sampling = request.containsKey("sampling")
                ? optionalMap(request, "sampling")
                : Map.of("rate", 0.001);
        Object rateValue = sampling.getOrDefault("rate", 0.001);
        double rate = rateValue instanceof Number number
                ? number.doubleValue()
                : Double.parseDouble(String.valueOf(rateValue));
        if (!Double.isFinite(rate) || rate <= 0 || rate > 1) {
            throw PlatformException.badRequest("INVALID_SAMPLING_RATE",
                    "sampling.rate must be greater than 0 and at most 1");
        }
        return json(sampling);
    }

    private String hash(Object value) {
        try {
            byte[] bytes = value instanceof String text
                    ? text.getBytes(StandardCharsets.UTF_8)
                    : mapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash value", e);
        }
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private String requiredString(Map<String, Object> request, String key, String defaultValue) {
        String value = optionalString(request, key, defaultValue);
        if (value == null || value.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        return value;
    }

    private String optionalString(Map<String, Object> request, String key, String defaultValue) {
        Object value = request.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private long requiredLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw PlatformException.badRequest("INVALID_FIELD", "Field must be a number: " + key);
        }
    }

    private long optionalLong(Map<String, Object> request, String key, long defaultValue) {
        Object value = request.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Long optionalLongObject(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw PlatformException.badRequest("INVALID_FIELD", "Field must be a number: " + key);
        }
    }

    private <E extends Enum<E>> E enumValue(Map<String, Object> request, String key, Class<E> type) {
        String value = requiredString(request, key, null);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw PlatformException.badRequest("INVALID_FIELD", "Invalid " + key + ": " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> optionalMap(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        if (value instanceof String text) {
            try {
                return mapper.readValue(text, new TypeReference<>() {
                });
            } catch (Exception e) {
                throw PlatformException.badRequest("INVALID_FIELD", "Field must be an object: " + key);
            }
        }
        throw PlatformException.badRequest("INVALID_FIELD", "Field must be an object: " + key);
    }

    private List<?> optionalList(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw PlatformException.badRequest("INVALID_FIELD", "Field must be an array: " + key);
    }

    private Map<String, Object> asMap(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        throw PlatformException.badRequest("INVALID_FIELD", "Field must be an object: " + key);
    }
}
