package com.example.kairo.platform.service;

import com.example.kairo.platform.domain.OperationPlanStatus;
import com.example.kairo.platform.fencing.FencingTokenService;
import com.example.kairo.platform.persistence.mapper.AttachRegistrationMapper;
import com.example.kairo.platform.persistence.mapper.PlatformCoreMapper;
import com.example.kairo.platform.persistence.mapper.RuleVersionLifecycleMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
public class PlatformCoreService {

    private static final String GENESIS_HASH = "GENESIS";
    private final PlatformCoreMapper platformCoreMapper;
    private final RbacService rbacService;
    private final FencingTokenService fencingTokenService;
    private final AttachRegistrationMapper attachRegistrationMapper;
    private final RuleVersionLifecycleMapper ruleVersionLifecycleMapper;
    private final BusinessIdService businessIdService;
    private final Clock clock;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Autowired
    public PlatformCoreService(PlatformCoreMapper platformCoreMapper, RbacService rbacService,
                               FencingTokenService fencingTokenService,
                               AttachRegistrationMapper attachRegistrationMapper,
                               RuleVersionLifecycleMapper ruleVersionLifecycleMapper,
                               BusinessIdService businessIdService) {
        this(platformCoreMapper, rbacService, fencingTokenService, attachRegistrationMapper, ruleVersionLifecycleMapper,
                businessIdService, Clock.systemUTC());
    }

    PlatformCoreService(PlatformCoreMapper platformCoreMapper, RbacService rbacService,
                        FencingTokenService fencingTokenService,
                        AttachRegistrationMapper attachRegistrationMapper,
                        RuleVersionLifecycleMapper ruleVersionLifecycleMapper,
                        BusinessIdService businessIdService, Clock clock) {
        this.platformCoreMapper = platformCoreMapper;
        this.rbacService = rbacService;
        this.fencingTokenService = fencingTokenService;
        this.attachRegistrationMapper = attachRegistrationMapper;
        this.ruleVersionLifecycleMapper = ruleVersionLifecycleMapper;
        this.businessIdService = businessIdService;
        this.clock = clock;
    }

    public Map<String, Object> health() {
        long started = System.nanoTime();
        platformCoreMapper.ping();
        long latencyMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("platformApi", Map.of("status", "UP", "latencyMs", 0));
        services.put("postgresql", Map.of("status", "UP", "latencyMs", latencyMs));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("checkedAt", clock.instant().toString());
        result.put("storage", "postgresql");
        result.put("services", services);
        result.put("instanceCount", count("instance"));
        result.put("ruleCount", count("rule"));
        result.put("operationPlanCount", count("operation_plan"));
        return result;
    }

    public List<Map<String, Object>> list(String table, String orderBy) {
        return normalizeRows(platformCoreMapper.list(table, orderBy));
    }

    public List<Map<String, Object>> listFencingTokens() {
        return normalizeRows(platformCoreMapper.listFencingTokens());
    }

    public List<Map<String, Object>> listAgents() {
        return normalizeRows(platformCoreMapper.listAgents());
    }

    @Transactional
    public Map<String, Object> issueFencingToken(RequestContext context, Map<String, Object> request) {
        String resourceType = requiredString(request, "resourceType", null);
        String resourceId = requiredString(request, "resourceId", null);
        String purpose = requiredString(request, "purpose", "state-transition");
        rbacService.require(context, fencingCapability(resourceType));
        long ttlSeconds = optionalLong(request, "ttlSeconds", 300);
        Map<String, Object> token = fencingTokenService.issue(context, resourceType, resourceId, purpose, ttlSeconds);
        recordAudit(context, "fencing_token.issue", resourceType, resourceId, 1,
                "", hash(token), "SUCCESS", optionalString(request, "reason", "issue fencing token"),
                Map.of("purpose", purpose, "sequence", token.get("sequence")));
        return token;
    }

    @Transactional
    public Map<String, Object> createInstance(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "INSTANCE_MANAGE");
        Instant now = clock.instant();
        String applicationId = requiredString(request, "applicationId", null);
        String environmentId = optionalString(request, "environmentId", null);
        requireExists("application", applicationId);
        if (environmentId != null) {
            validateEnvironment(applicationId, environmentId);
        }
        String applicationName = String.valueOf(getById("application", applicationId).get("name"));
        String id = optionalString(request, "id", null);
        if (id == null || id.isBlank()) {
            id = businessIdService.nextId("instance", instanceBusinessName(request, applicationName));
        }
        String nickname = uniqueInstanceNickname(
                optionalString(request, "nickname", applicationName), applicationName, now);
        platformCoreMapper.insertInstance(id, applicationId, environmentId, nickname,
                requiredString(request, "hostname", null),
                requiredString(request, "processId", "unknown"),
                requiredString(request, "runtime", "java"),
                requiredString(request, "status", "ACTIVE"),
                jsonValue(request, "labels", Map.of()),
                timestamp(now));
        insertLabels(id, optionalMap(request, "labels"), now);
        recordAudit(context, "instance.create", "instance", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create instance"),
                Map.of("hostname", requiredString(request, "hostname", null)));
        return getById("instance", id);
    }

    @Transactional
    public Map<String, Object> updateInstanceNickname(String instanceId, RequestContext context,
                                                      Map<String, Object> request) {
        rbacService.require(context, "INSTANCE_MANAGE");
        Map<String, Object> before = getById("instance", instanceId);
        String nickname = normalizeNickname(requiredString(request, "nickname", null));
        Instant now = clock.instant();
        try {
            int updated = platformCoreMapper.updateInstanceNickname(instanceId, nickname, timestamp(now));
            if (updated != 1) {
                throw PlatformException.notFound("instance", instanceId);
            }
        } catch (DuplicateKeyException e) {
            throw PlatformException.conflict("INSTANCE_NICKNAME_CONFLICT",
                    "实例昵称已存在，请换一个全局唯一的昵称", Map.of("nickname", nickname));
        }
        Map<String, Object> after = getById("instance", instanceId);
        recordAudit(context, "instance.nickname.update", "instance", instanceId, 1,
                hash(before), hash(after), "SUCCESS",
                optionalString(request, "reason", "update instance nickname"),
                Map.of("nickname", nickname));
        return after;
    }

    @Transactional
    public Map<String, Object> registerAgentRuntime(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        RegistrationApplication registrationApplication = resolveRegistrationApplication(request);
        String applicationId = registrationApplication.applicationId();
        String environmentId = resolveEnvironmentId(applicationId, optionalString(request, "environmentId", null),
                optionalString(request, "environmentName", null));
        String processStartId = requiredString(request, "processStartId", null);
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plusSeconds(30);
        String requestedInstanceId = optionalString(request, "instanceId", null);
        List<Map<String, Object>> existingInstances =
                platformCoreMapper.findInstanceForRegistration(requestedInstanceId, processStartId);
        if (requestedInstanceId != null && existingInstances.isEmpty()) {
            throw PlatformException.notFound("instance", requestedInstanceId);
        }
        String instanceId;
        if (existingInstances.isEmpty()) {
            instanceId = businessIdService.nextId("instance", registrationApplication.applicationName());
            String nickname = uniqueInstanceNickname(
                    optionalString(request, "nickname", registrationApplication.applicationName()),
                    registrationApplication.applicationName(), now);
            platformCoreMapper.insertRuntimeInstance(instanceId, applicationId, environmentId, nickname,
                    requiredString(request, "hostname", null),
                    requiredString(request, "processId", null),
                    requiredString(request, "runtime", "java"),
                    environmentId == null ? "PENDING_ASSIGNMENT" : "ACTIVE",
                    jsonValue(request, "labels", Map.of()),
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
            platformCoreMapper.updateRuntimeInstance(instanceId, applicationId, environmentId,
                    requiredString(request, "hostname", null),
                    requiredString(request, "processId", null),
                    requiredString(request, "runtime", "java"),
                    timestamp(now),
                    processStartId,
                    timestamp(Instant.ofEpochMilli(optionalLong(request, "jvmStartedAtEpochMillis",
                            now.toEpochMilli()))),
                    requiredString(request, "javaVersion", "unknown"),
                    requiredString(request, "loadMode", "unknown"),
                    requiredString(request, "agentVersion", "unknown"),
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(leaseExpiresAt));
        }

        String sidecarIdForAgent = latestSidecarId(instanceId);
        List<Map<String, Object>> existingAgents = platformCoreMapper.firstAgentByInstance(instanceId);
        String agentId;
        if (existingAgents.isEmpty()) {
            agentId = "agent-" + UUID.randomUUID();
            platformCoreMapper.insertRuntimeAgent(agentId, instanceId, sidecarIdForAgent, "ACTIVE",
                    requiredString(request, "agentVersion", "unknown"),
                    requiredString(request, "bootstrapVersion", "embedded"),
                    requiredString(request, "listenHost", "127.0.0.1"),
                    (int) optionalLong(request, "listenPort", 0),
                    "platform-authenticated",
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(now), timestamp(leaseExpiresAt));
        } else {
            agentId = String.valueOf(normalizeRow(existingAgents.get(0)).get("id"));
            platformCoreMapper.updateRuntimeAgent(agentId, sidecarIdForAgent,
                    requiredString(request, "agentVersion", "unknown"),
                    requiredString(request, "bootstrapVersion", "embedded"),
                    requiredString(request, "listenHost", "127.0.0.1"),
                    (int) optionalLong(request, "listenPort", 0),
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(now), timestamp(leaseExpiresAt));
            platformCoreMapper.deleteAgentCapabilities(agentId);
        }
        insertAgentCapabilities(agentId, optionalList(request, "capabilities"), now);
        int restoredOperationPlans = restoreAgentGoneOperationPlans(context, instanceId, now);
        recordAudit(context, "agent_instance.self_register", "agent_instance", agentId, 1,
                "", hash(request), "SUCCESS", "Agent 运行时自动注册",
                Map.of("instanceId", instanceId, "applicationId", applicationId,
                        "environmentAssigned", environmentId != null,
                        "restoredOperationPlans", restoredOperationPlans));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("agentId", agentId);
        result.put("applicationId", applicationId);
        result.put("projectName", registrationApplication.projectName());
        result.put("applicationName", registrationApplication.applicationName());
        result.put("environmentId", environmentId);
        result.put("status", environmentId == null ? "PENDING_ASSIGNMENT" : "ACTIVE");
        result.put("leaseExpiresAt", leaseExpiresAt.toString());
        result.put("restoredOperationPlans", restoredOperationPlans);
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
        String projectId = platformCoreMapper.findProjectId(projectName);
        if (projectId == null) {
            projectId = "project-" + UUID.randomUUID();
            platformCoreMapper.insertProject(projectId, projectName, timestamp(clock.instant()));
        }

        String resolvedProjectId = projectId;
        String resolvedApplicationId = platformCoreMapper.findApplicationId(resolvedProjectId, applicationName);
        if (resolvedApplicationId == null) {
            resolvedApplicationId = "application-" + UUID.randomUUID();
            platformCoreMapper.insertApplication(resolvedApplicationId, resolvedProjectId, applicationName,
                    timestamp(clock.instant()));
        }
        ensureStandardEnvironments(resolvedApplicationId);
        return new RegistrationApplication(resolvedApplicationId, projectName, applicationName);
    }

    private void ensureStandardEnvironments(String applicationId) {
        for (String type : List.of("dev", "sit", "uat", "prod")) {
            if (platformCoreMapper.countEnvironmentType(applicationId, type) > 0) {
                continue;
            }
            platformCoreMapper.insertEnvironment("environment-" + UUID.randomUUID(),
                    applicationId, type, type, timestamp(clock.instant()));
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
        platformCoreMapper.assignInstanceEnvironment(instanceId, environmentId, timestamp(now));
        recordAudit(context, "instance.assign_environment", "instance", instanceId, 1,
                hash(instance), hash(Map.of("environmentId", environmentId)), "SUCCESS",
                "分配运行环境", Map.of("environmentId", environmentId));
        return getById("instance", instanceId);
    }

    @Transactional
    public Map<String, Object> createSidecar(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "sidecar-" + UUID.randomUUID());
        platformCoreMapper.insertSidecar(id, optionalString(request, "instanceId", null),
                requiredString(request, "status", "ACTIVE"),
                requiredString(request, "sidecarVersion", "unknown"),
                requiredString(request, "endpoint", null),
                jsonValue(request, "capabilities", List.of()),
                timestamp(now));
        recordAudit(context, "sidecar_instance.create", "sidecar_instance", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create sidecar"),
                Map.of("endpoint", requiredString(request, "endpoint", null)));
        return getById("sidecar_instance", id);
    }

    @Transactional
    public Map<String, Object> registerAttachSidecar(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plusSeconds(optionalLong(request, "leaseSeconds", 30L));
        String endpoint = requiredString(request, "endpoint", null);
        String executorId = optionalString(request, "executorId", null);
        if (executorId == null || executorId.isBlank()) {
            executorId = "executor-" + UUID.nameUUIDFromBytes(endpoint.getBytes(StandardCharsets.UTF_8));
        }
        upsertAttachExecutor(executorId, request, endpoint, leaseExpiresAt, now);

        List<?> requestedTargets = optionalList(request, "targets");
        if (requestedTargets.isEmpty()) {
            requestedTargets = List.of(request);
        }
        List<Map<String, Object>> registeredTargets = new ArrayList<>();
        for (Object item : requestedTargets) {
            Map<String, Object> targetRequest = mergedTargetRequest(request, asMap(item, "targets"));
            registeredTargets.add(registerAttachTarget(executorId, targetRequest, endpoint, leaseExpiresAt, now));
        }
        if (registeredTargets.isEmpty()) {
            throw PlatformException.badRequest("ATTACH_TARGET_REQUIRED", "Attach executor must register at least one target");
        }
        deleteStaleAttachTargets(executorId, registeredTargets, now);
        attachRegistrationMapper.markSidecarsOfflineForOfflineTargets(executorId, timestamp(now));
        recordAudit(context, "attach_executor.self_register", "attach_executor", executorId, 1,
                "", hash(request), "SUCCESS", "Attach 执行器自动注册",
                Map.of("targetCount", registeredTargets.size(), "endpoint", endpoint));
        Map<String, Object> firstTarget = registeredTargets.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executorId", executorId);
        result.put("executorType", requiredString(request, "executorType", "SIDECAR_CONTAINER"));
        result.put("endpoint", endpoint);
        result.put("targets", registeredTargets);
        result.put("targetCount", registeredTargets.size());
        result.put("leaseExpiresAt", leaseExpiresAt.toString());
        result.put("instanceId", firstTarget.get("instanceId"));
        result.put("sidecarId", firstTarget.get("sidecarId"));
        result.put("applicationId", firstTarget.get("applicationId"));
        result.put("projectName", firstTarget.get("projectName"));
        result.put("applicationName", firstTarget.get("applicationName"));
        result.put("environmentId", firstTarget.get("environmentId"));
        result.put("status", firstTarget.get("status"));
        return result;
    }

    private void upsertAttachExecutor(String executorId, Map<String, Object> request, String endpoint,
                                      Instant leaseExpiresAt, Instant now) {
        Map<String, Object> existing = attachRegistrationMapper.findAttachExecutor(executorId);
        if (existing == null || existing.isEmpty()) {
            attachRegistrationMapper.insertAttachExecutor(executorId,
                    requiredString(request, "executorType", "SIDECAR_CONTAINER"),
                    requiredString(request, "hostname", "unknown"),
                    endpoint,
                    requiredString(request, "status", "ACTIVE"),
                    requiredString(request, "sidecarVersion", "unknown"),
                    jsonValue(request, "capabilities", List.of("ATTACH_AGENT", "RELOAD_AGENT")),
                    timestamp(now), timestamp(leaseExpiresAt), timestamp(now), timestamp(now));
        } else {
            attachRegistrationMapper.updateAttachExecutor(executorId,
                    requiredString(request, "executorType", "SIDECAR_CONTAINER"),
                    requiredString(request, "hostname", "unknown"),
                    endpoint,
                    requiredString(request, "status", "ACTIVE"),
                    requiredString(request, "sidecarVersion", "unknown"),
                    jsonValue(request, "capabilities", List.of("ATTACH_AGENT", "RELOAD_AGENT")),
                    timestamp(now), timestamp(leaseExpiresAt), timestamp(now));
        }
    }

    private Map<String, Object> registerAttachTarget(String executorId, Map<String, Object> request, String endpoint,
                                                     Instant leaseExpiresAt, Instant now) {
        RegistrationApplication registrationApplication = resolveRegistrationApplication(request);
        String applicationId = registrationApplication.applicationId();
        String environmentId = resolveEnvironmentId(applicationId, optionalString(request, "environmentId", null),
                optionalString(request, "environmentName", null));
        String processId = requiredString(request, "processId", null);
        String hostname = requiredString(request, "hostname", "unknown");
        String processStartId = optionalString(request, "processStartId",
                registrationApplication.applicationName() + ":" + hostname + ":" + processId);
        Map<String, Object> existingInstance = attachRegistrationMapper.findInstanceByProcessStartId(processStartId);
        String instanceId;
        if (existingInstance == null || existingInstance.isEmpty()) {
            instanceId = businessIdService.nextId("instance", registrationApplication.applicationName());
            String nickname = uniqueInstanceNickname(
                    optionalString(request, "nickname", registrationApplication.applicationName()),
                    registrationApplication.applicationName(), now);
            attachRegistrationMapper.insertAttachTargetInstance(
                    instanceId,
                    applicationId,
                    environmentId,
                    nickname,
                    requiredString(request, "hostname", null),
                    requiredString(request, "processId", null),
                    requiredString(request, "runtime", "java"),
                    environmentId == null ? "PENDING_ASSIGNMENT" : "ACTIVE",
                    jsonValue(request, "labels", Map.of()),
                    timestamp(now),
                    timestamp(now),
                    timestamp(now),
                    processStartId,
                    requiredString(request, "javaVersion", "unknown"),
                    "none",
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(leaseExpiresAt),
                    environmentId == null ? "PENDING_ASSIGNMENT" : "ASSIGNED");
        } else {
            Map<String, Object> existing = normalizeRow(existingInstance);
            instanceId = String.valueOf(existing.get("id"));
            attachRegistrationMapper.updateAttachTargetInstance(instanceId,
                    applicationId,
                    environmentId,
                    requiredString(request, "hostname", null),
                    requiredString(request, "processId", null),
                    requiredString(request, "runtime", "java"),
                    timestamp(now), timestamp(now),
                    requiredString(request, "javaVersion", "unknown"),
                    jsonValue(request, "capabilities", List.of()),
                    timestamp(leaseExpiresAt));
        }

        Map<String, Object> existingSidecar =
                attachRegistrationMapper.findSidecarByInstanceAndExecutor(instanceId, executorId);
        String sidecarId;
        if (existingSidecar == null || existingSidecar.isEmpty()) {
            sidecarId = "sidecar-" + UUID.randomUUID();
            attachRegistrationMapper.insertAttachSidecar(sidecarId, instanceId, executorId, "ACTIVE",
                    requiredString(request, "sidecarVersion", "unknown"),
                    endpoint,
                    jsonValue(request, "capabilities", List.of("ATTACH_AGENT")),
                    timestamp(now), timestamp(now), timestamp(now));
        } else {
            sidecarId = String.valueOf(normalizeRow(existingSidecar).get("id"));
            attachRegistrationMapper.updateAttachSidecar(sidecarId,
                    executorId,
                    requiredString(request, "sidecarVersion", "unknown"),
                    endpoint,
                    jsonValue(request, "capabilities", List.of("ATTACH_AGENT")),
                    timestamp(now), timestamp(now));
        }
        upsertAttachExecutorTarget(executorId, instanceId, request, processId, now);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("sidecarId", sidecarId);
        result.put("executorId", executorId);
        result.put("applicationId", applicationId);
        result.put("projectName", registrationApplication.projectName());
        result.put("applicationName", registrationApplication.applicationName());
        result.put("environmentId", environmentId);
        result.put("status", environmentId == null ? "PENDING_ASSIGNMENT" : "ACTIVE");
        result.put("processId", processId);
        result.put("agentJar", requiredString(request, "agentJar", "/app/kairo-agent-bootstrap.jar"));
        return result;
    }

    private void upsertAttachExecutorTarget(String executorId, String instanceId, Map<String, Object> request,
                                            String processId, Instant now) {
        String agentJar = requiredString(request, "agentJar", "/app/kairo-agent-bootstrap.jar");
        attachRegistrationMapper.upsertAttachExecutorTarget(executorId, instanceId, processId, agentJar,
                requiredString(request, "runtime", "java"),
                requiredString(request, "javaVersion", "unknown"),
                requiredString(request, "targetStatus", "ACTIVE"),
                jsonValue(request, "capabilities", List.of("ATTACH_AGENT")),
                timestamp(now), timestamp(now), timestamp(now));
    }

    private void deleteStaleAttachTargets(String executorId, List<Map<String, Object>> registeredTargets,
                                          Instant now) {
        List<String> activeInstanceIds = registeredTargets.stream()
                .map(target -> String.valueOf(target.get("instanceId")))
                .toList();
        if (activeInstanceIds.isEmpty()) {
            return;
        }
        List<Map<String, Object>> staleTargets = normalizeRows(
                attachRegistrationMapper.findStaleAttachTargets(executorId, activeInstanceIds));
        for (Map<String, Object> target : staleTargets) {
            Object instanceId = target.get("instance_id");
            attachRegistrationMapper.markSidecarsOfflineForTarget(executorId, instanceId, timestamp(now));
            Integer activeAgentCount = attachRegistrationMapper.countActiveAgentsByInstance(instanceId, timestamp(now));
            if (activeAgentCount != null && activeAgentCount > 0) {
                attachRegistrationMapper.markAttachExecutorTargetOffline(executorId, instanceId, timestamp(now));
                continue;
            }
            markExecutionsAbandonedForInstance(instanceId, now);
            markPlansAbandonedForInstanceIfNoLiveTargets(instanceId, now);
            platformCoreMapper.deleteInstanceRuleRuntimeStatus(instanceId);
            platformCoreMapper.deleteInstanceRuleBindings(instanceId);
            platformCoreMapper.deleteInstanceLabels(instanceId);
            platformCoreMapper.deleteInstanceAssetClaims(instanceId);
            platformCoreMapper.deleteInstanceAttachTargets(instanceId);
            platformCoreMapper.deleteInstanceSidecarsWithoutAgents(instanceId);
            platformCoreMapper.archiveInstance(instanceId, timestamp(now));
        }
    }

    private void markExecutionsAbandonedForInstance(Object instanceId, Instant now) {
        platformCoreMapper.abandonExecutionsForInstance(instanceId, timestamp(now));
    }

    private void markPlansAbandonedForInstanceIfNoLiveTargets(Object instanceId, Instant now) {
        platformCoreMapper.abandonPlansForInstanceWithoutLiveTargets(instanceId, timestamp(now));
    }

    private Map<String, Object> mergedTargetRequest(Map<String, Object> envelope, Map<String, Object> target) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : envelope.entrySet()) {
            if (!"targets".equals(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        merged.putAll(target);
        return merged;
    }

    @Transactional
    public Map<String, Object> createAgent(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        Instant now = clock.instant();
        String id = optionalString(request, "id", "agent-" + UUID.randomUUID());
        String agentStatus = requiredString(request, "status", "ACTIVE");
        platformCoreMapper.insertManualAgent(id, optionalString(request, "instanceId", null),
                optionalString(request, "sidecarId", null),
                agentStatus,
                requiredString(request, "agentVersion", "unknown"),
                requiredString(request, "bootstrapVersion", "unknown"),
                requiredString(request, "listenHost", "127.0.0.1"),
                (int) optionalLong(request, "listenPort", 0),
                requiredString(request, "tokenHash", "unregistered"),
                jsonValue(request, "capabilities", List.of()),
                timestamp(now));
        insertAgentCapabilities(id, optionalList(request, "capabilities"), now);
        String instanceId = optionalString(request, "instanceId", null);
        int restoredOperationPlans = instanceId == null || !isOnlineRuntimeStatus(agentStatus)
                ? 0
                : restoreAgentGoneOperationPlans(context, instanceId, now);
        recordAudit(context, "agent_instance.create", "agent_instance", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create agent"),
                Map.of("status", agentStatus,
                        "restoredOperationPlans", restoredOperationPlans));
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
        platformCoreMapper.updateAgentHeartbeat(id, status, timestamp(now), timestamp(leaseExpiresAt));
        platformCoreMapper.updateInstanceHeartbeatByAgent(id, timestamp(now), timestamp(leaseExpiresAt));
        String instanceId = platformCoreMapper.instanceIdByAgent(id);
        int restoredOperationPlans = instanceId == null || !isOnlineRuntimeStatus(status)
                ? 0
                : restoreAgentGoneOperationPlans(context, instanceId, now);
        platformCoreMapper.insertAgentHeartbeat("agent-heartbeat-" + UUID.randomUUID(), id, status,
                jsonValue(request, "metrics", Map.of()), timestamp(now));
        recordAudit(context, "agent_instance.heartbeat", "agent_instance", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "agent heartbeat"),
                Map.of("status", status, "restoredOperationPlans", restoredOperationPlans));
        return safeAgent(getById("agent_instance", id));
    }

    private boolean isOnlineRuntimeStatus(String status) {
        return "ACTIVE".equalsIgnoreCase(status) || "ONLINE".equalsIgnoreCase(status);
    }

    private int restoreAgentGoneOperationPlans(RequestContext context, String instanceId, Instant now) {
        List<Map<String, Object>> operations = normalizeRows(platformCoreMapper.agentGoneOperations(instanceId));
        int restored = 0;
        for (Map<String, Object> operation : operations) {
            String operationId = String.valueOf(operation.get("id"));
            long version = ((Number) operation.get("version")).longValue();
            int updated = platformCoreMapper.restoreAgentGoneOperation(operationId,
                    context.actor(), timestamp(now), version);
            if (updated == 0) {
                continue;
            }
            platformCoreMapper.resetRestoredExecution(operationId, instanceId, context.actor(), timestamp(now));
            Map<String, Object> current = getById("operation_plan", operationId);
            recordEvent(context, "operation_plan.restore_after_agent_register", "operation_plan",
                    operationId, ((Number) current.get("version")).longValue(),
                    operation, current, "RUNNING",
                    "Agent 重新注册，自动恢复非手动卸载的发布计划",
                    Map.of("instanceId", instanceId));
            restored++;
        }
        return restored;
    }

    @Transactional
    public Map<String, Object> createRule(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "RULE_MANAGE");
        Instant now = clock.instant();
        rejectClientProvidedRuleId(request);
        String id = businessIdService.nextId("rule", ruleBusinessName(request));
        ApplicationEnvironment scope = requireApplicationEnvironment(request);
        platformCoreMapper.insertRule(id, scope.applicationId(), scope.environmentId(),
                requiredString(request, "name", null),
                ruleLifecycleStatus(request, "status", "ENABLED"),
                context.actor(),
                timestamp(now));
        insertRuleVersion(context, id, 1L, request, now);
        recordAudit(context, "rule.create", "rule", id, 1,
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
        platformCoreMapper.updateRuleAfterVersion(ruleId, version, context.actor(), timestamp(now));
        recordAudit(context, "rule_version.create", "rule", ruleId, version,
                hash(rule), hash(request), "SUCCESS", optionalString(request, "reason", "create rule version"),
                Map.of("version", version));
        return getById("rule_version", ruleId + ":" + version);
    }

    @Transactional
    public Map<String, Object> disableRule(String ruleId, RequestContext context) {
        throw PlatformException.methodNotAllowed("RULE_AGGREGATE_LIFECYCLE_DISABLED",
                "规则是版本聚合对象，请在规则版本台账中停用具体版本");
    }

    @Transactional
    public Map<String, Object> enableRule(String ruleId, RequestContext context) {
        throw PlatformException.methodNotAllowed("RULE_AGGREGATE_LIFECYCLE_DISABLED",
                "规则是版本聚合对象，请在规则版本台账中启用具体版本");
    }

    @Transactional
    public Map<String, Object> disableRuleVersion(String ruleId, long version, RequestContext context) {
        rbacService.require(context, "RULE_MANAGE");
        getById("rule", ruleId);
        Map<String, Object> before = single(normalizeRows(optionalRow(ruleVersionLifecycleMapper.findRuleVersion(ruleId, version))),
                "rule_version", ruleId + ":" + version);
        String currentStatus = String.valueOf(before.get("status"));
        if ("DISABLED".equals(currentStatus)) {
            return before;
        }
        Instant now = clock.instant();
        Instant autoDeleteAt = now.plusSeconds(30L * 24 * 60 * 60);
        ruleVersionLifecycleMapper.disableRuleVersion(ruleId, version, timestamp(now), timestamp(autoDeleteAt), currentStatus);
        refreshRuleVersionPointers(ruleId, context);
        Map<String, Object> disabled = single(normalizeRows(optionalRow(ruleVersionLifecycleMapper.findRuleVersion(ruleId, version))),
                "rule_version", ruleId + ":" + version);
        recordAudit(context, "rule_version.disable", "rule", ruleId, version,
                hash(before), hash(disabled), "SUCCESS", "disable rule version",
                Map.of("version", version, "autoDeleteAt", autoDeleteAt.toString()));
        return disabled;
    }

    @Transactional
    public Map<String, Object> enableRuleVersion(String ruleId, long version, RequestContext context) {
        rbacService.require(context, "RULE_MANAGE");
        getById("rule", ruleId);
        Map<String, Object> before = single(normalizeRows(optionalRow(ruleVersionLifecycleMapper.findRuleVersion(ruleId, version))),
                "rule_version", ruleId + ":" + version);
        String currentStatus = String.valueOf(before.get("status"));
        if (!"DISABLED".equals(currentStatus)) {
            return before;
        }
        String restoredStatus = "ENABLED";
        ruleVersionLifecycleMapper.enableRuleVersion(ruleId, version, restoredStatus);
        refreshRuleVersionPointers(ruleId, context);
        Map<String, Object> enabled = single(normalizeRows(optionalRow(ruleVersionLifecycleMapper.findRuleVersion(ruleId, version))),
                "rule_version", ruleId + ":" + version);
        recordAudit(context, "rule_version.enable", "rule", ruleId, version,
                hash(before), hash(enabled), "SUCCESS", "enable rule",
                Map.of("version", version, "status", restoredStatus));
        return enabled;
    }

    @Transactional
    public Map<String, Object> deleteRule(String ruleId, RequestContext context) {
        rbacService.require(context, "RULE_MANAGE");
        Map<String, Object> rule = getById("rule", ruleId);
        int versionCount = platformCoreMapper.countRuleVersions(ruleId);
        int deletedCapabilities = platformCoreMapper.deleteRuleCapabilities(ruleId);
        int deletedTargets = platformCoreMapper.deleteRuleTargets(ruleId);
        int deletedRuntimeStatuses = platformCoreMapper.deleteRuleRuntimeStatuses(ruleId);
        int deletedBindings = platformCoreMapper.deleteRuleBindings(ruleId);
        int deletedLocks = platformCoreMapper.deleteRuleLocks(ruleId);
        int deletedVersions = platformCoreMapper.deleteRuleVersions(ruleId);
        int deletedRules = platformCoreMapper.deleteRule(ruleId);
        recordAudit(context, "rule.delete", "rule", ruleId, 1,
                hash(rule), "", "SUCCESS", "delete rule",
                Map.of("versionCount", versionCount));
        return Map.of(
                "ruleId", ruleId,
                "rulesDeleted", deletedRules,
                "versionsDeleted", deletedVersions,
                "targetsDeleted", deletedTargets,
                "capabilitiesDeleted", deletedCapabilities,
                "runtimeStatusesDeleted", deletedRuntimeStatuses,
                "bindingsDeleted", deletedBindings,
                "locksDeleted", deletedLocks
        );
    }

    @Transactional
    public Map<String, Object> deleteRuleVersions(String ruleId, List<Long> versions, RequestContext context) {
        rbacService.require(context, "RULE_MANAGE");
        getById("rule", ruleId);
        List<Long> distinctVersions = versions.stream().distinct().sorted().toList();
        if (distinctVersions.isEmpty()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "缺少待删除规则版本");
        }
        int totalVersions = platformCoreMapper.countRuleVersions(ruleId);
        if (distinctVersions.size() >= totalVersions) {
            throw PlatformException.conflict("RULE_VERSION_DELETE_ALL",
                    "不能删除规则的全部版本；如需删除所有版本，请删除规则本身",
                    Map.of("ruleId", ruleId));
        }
        int matchedVersions = platformCoreMapper.countRuleVersionsIn(ruleId, distinctVersions);
        if (matchedVersions != distinctVersions.size()) {
            throw PlatformException.notFound("rule_version", ruleId + ":" + distinctVersions);
        }
        int deletedCapabilities = platformCoreMapper.deleteRuleCapabilitiesByVersions(ruleId, distinctVersions);
        int deletedTargets = platformCoreMapper.deleteRuleTargetsByVersions(ruleId, distinctVersions);
        int deletedRuntimeStatuses = platformCoreMapper.deleteRuleRuntimeStatusesByVersions(ruleId, distinctVersions);
        int deletedBindings = platformCoreMapper.deleteRuleBindingsByVersions(ruleId, distinctVersions);
        int deletedVersions = platformCoreMapper.deleteRuleVersionsByVersions(ruleId, distinctVersions);
        refreshRuleVersionPointers(ruleId, context);
        recordAudit(context, "rule_version.delete", "rule", ruleId, 1,
                "", hash(Map.of("versions", distinctVersions)), "SUCCESS",
                "delete rule versions", Map.of("versions", distinctVersions));
        return Map.of(
                "ruleId", ruleId,
                "versions", distinctVersions,
                "versionsDeleted", deletedVersions,
                "targetsDeleted", deletedTargets,
                "capabilitiesDeleted", deletedCapabilities,
                "runtimeStatusesDeleted", deletedRuntimeStatuses,
                "bindingsDeleted", deletedBindings
        );
    }

    @Transactional
    public Map<String, Object> createOperationPlan(RequestContext context, Map<String, Object> request) {
        rbacService.require(context, "ROLLOUT_MANAGE");
        Instant now = clock.instant();
        rejectClientProvidedDeliveryId(request);
        String applicationId = requiredString(request, "applicationId", null);
        String environmentId = requiredString(request, "environmentId", null);
        String resourceType = requiredString(request, "resourceType", null);
        String resourceId = requiredString(request, "resourceId", null);
        long resourceVersion = requiredLong(request, "resourceVersion");
        requireExists("application", applicationId);
        validateEnvironment(applicationId, environmentId);
        validateRolloutResource(applicationId, environmentId, resourceType, resourceId, resourceVersion);
        String id = businessIdService.nextId("operation_plan", rolloutBusinessName(resourceType, resourceId));
        platformCoreMapper.insertOperationPlan(id, applicationId, environmentId,
                requiredString(request, "planType", "RULE_ROLLOUT"),
                resourceType,
                resourceId,
                resourceVersion,
                OperationPlanStatus.DRAFT.name(),
                1L,
                jsonValue(request, "strategy", Map.of(
                        "targetMode", "ALL_ACTIVE_INSTANCES",
                        "automaticUnload", true
                )),
                context.actor(),
                timestamp(now));
        recordAudit(context, "operation_plan.create", "operation_plan", id, 1,
                "", hash(request), "SUCCESS", optionalString(request, "reason", "create operation plan"),
                Map.of("status", OperationPlanStatus.DRAFT.name()));
        return getById("operation_plan", id);
    }

    private void rejectClientProvidedDeliveryId(Map<String, Object> request) {
        Object id = request.get("id");
        if (id != null && !String.valueOf(id).isBlank()) {
            throw PlatformException.badRequest("CLIENT_DELIVERY_ID_DISABLED",
                    "发布相关 ID 由平台按业务缩写、日期和顺序号生成，禁止客户端传入随机或自定义 ID");
        }
    }

    private void rejectClientProvidedRuleId(Map<String, Object> request) {
        Object id = request.get("id");
        if (id != null && !String.valueOf(id).isBlank()) {
            throw PlatformException.badRequest("CLIENT_RULE_ID_DISABLED",
                    "规则 ID 由平台按业务缩写、日期和顺序号生成，禁止客户端传入随机或自定义 ID");
        }
    }

    private String instanceBusinessName(Map<String, Object> request, String applicationName) {
        return String.join(" ",
                optionalString(request, "nickname", applicationName),
                optionalString(request, "hostname", ""),
                optionalString(request, "processId", ""));
    }

    private String ruleBusinessName(Map<String, Object> request) {
        String explicitCode = optionalString(request, "businessCode", null);
        if (explicitCode == null || explicitCode.isBlank()) {
            explicitCode = optionalString(request, "businessAbbreviation", null);
        }
        if (explicitCode != null && !explicitCode.isBlank()) {
            return explicitCode;
        }
        Map<String, Object> target = primaryRuleTarget(request);
        String className = optionalString(target, "className", optionalString(target, "class_name", ""));
        String methodName = optionalString(target, "methodName", optionalString(target, "method_name", ""));
        String targetName = String.join(" ", simpleClassName(className), methodName).trim();
        String name = optionalString(request, "name", "rule");
        return targetName.isBlank() ? name : targetName + " " + name;
    }

    private Map<String, Object> primaryRuleTarget(Map<String, Object> request) {
        List<?> targets = optionalList(request, "targets");
        if (!targets.isEmpty()) {
            return asMap(targets.get(0), "targets");
        }
        Object target = request.get("target");
        return target == null ? Map.of() : asMap(target, "target");
    }

    private String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "";
        }
        String normalized = className.replace('$', '.');
        int lastDot = normalized.lastIndexOf('.');
        return lastDot >= 0 ? normalized.substring(lastDot + 1) : normalized;
    }

    public String rolloutBusinessName(String resourceType, String resourceId) {
        if ("rule".equals(resourceType)) {
            String name = platformCoreMapper.ruleName(resourceId);
            if (name != null) {
                if (!"RL".equals(businessIdService.abbreviation(name))) {
                    return name;
                }
            }
        }
        return resourceId;
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
        long newVersion = currentVersion + 1;
        Instant now = clock.instant();
        int updated = platformCoreMapper.transitionOperationPlan(id, targetStatus.name(), newVersion,
                context.actor(), timestamp(now), expectedStatus.name(), expectedVersion);
        if (updated == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "发布计划状态或版本已发生变化，请刷新后重试",
                    Map.of("id", id, "expectedStatus", expectedStatus.name(),
                            "expectedVersion", expectedVersion));
        }
        Map<String, Object> updatedPlan = getById("operation_plan", id);
        recordAudit(context, "operation_plan.transition", "operation_plan", id, newVersion,
                hash(original), hash(updatedPlan),
                "SUCCESS", reason,
                Map.of("from", expectedStatus.name(), "to", targetStatus.name(),
                        "fencingToken", fencingToken));
        return updatedPlan;
    }

    public void recordEvent(RequestContext context, String action, String resourceType, String resourceId,
                            long resourceVersion, Object before, Object after, String result,
                            String reason, Map<String, Object> details) {
        recordAudit(context, action, resourceType, resourceId, resourceVersion,
                hash(before == null ? "" : before), hash(after == null ? "" : after),
                result, reason, details);
    }

    public String stableHash(Object value) {
        return hash(value == null ? "" : value);
    }

    private void insertLabels(String instanceId, Map<String, Object> labels, Instant now) {
        labels.forEach((key, value) -> platformCoreMapper.insertLabel(
                "instance-label-" + UUID.randomUUID(), instanceId, key, String.valueOf(value), timestamp(now)));
    }

    private void insertAgentCapabilities(String agentId, List<?> capabilities, Instant now) {
        for (Object capability : capabilities) {
            String capabilityName = String.valueOf(capability);
            platformCoreMapper.insertAgentCapability("agent-capability-" + UUID.randomUUID(), agentId, capabilityName,
                    json(Map.of()), timestamp(now));
        }
    }

    private void insertRuleVersion(RequestContext context, String ruleId, long version,
                                   Map<String, Object> request, Instant now) {
        String versionId = ruleId + ":" + version;
        Object script = request.getOrDefault("script", Map.of());
        platformCoreMapper.insertRuleVersion(versionId, ruleId, version,
                ruleLifecycleStatus(request, "versionStatus", "ENABLED"),
                requiredString(request, "riskLevel", "MEDIUM"),
                jsonValue(request, "matcher", Map.of()),
                optionalString(request, "scriptHash", hash(script)),
                json(script),
                jsonValue(request, "governance", Map.of()),
                context.actor(),
                timestamp(now));
        List<?> targets = optionalList(request, "targets");
        if (targets.isEmpty() && request.containsKey("target")) {
            targets = List.of(request.get("target"));
        }
        for (Object item : targets) {
            Map<String, Object> target = asMap(item, "targets");
            platformCoreMapper.insertRuleTarget("rule-target-" + UUID.randomUUID(), versionId,
                    requiredString(target, "protocol", "JAVA_METHOD"),
                    requiredString(target, "className", null),
                    requiredString(target, "methodName", null),
                    jsonValue(target, "matcher", Map.of()),
                    timestamp(now));
        }
        for (Object capability : optionalList(request, "capabilities")) {
            platformCoreMapper.insertRuleCapability("rule-capability-" + UUID.randomUUID(),
                    versionId, String.valueOf(capability), timestamp(now));
        }
    }

    private void validateRolloutResource(String applicationId, String environmentId,
                                         String resourceType, String resourceId,
                                         long resourceVersion) {
        if (!"rule".equals(resourceType)) {
            throw PlatformException.badRequest("INVALID_RESOURCE_TYPE",
                    "当前发布计划仅支持规则资源");
        }
        if (ruleVersionLifecycleMapper.countRuleInScope(resourceId, applicationId, environmentId) == 0) {
            throw PlatformException.badRequest("INVALID_ROLLOUT_RESOURCE",
                    "所选规则不属于当前应用和环境");
        }
        if (ruleVersionLifecycleMapper.countEnabledRuleInScope(resourceId, applicationId, environmentId) == 0) {
            throw PlatformException.badRequest("RULE_DISABLED",
                    "所选规则已停用，不能创建发布计划");
        }
        if (ruleVersionLifecycleMapper.countEnabledRuleVersion(resourceId, resourceVersion) == 0) {
            throw PlatformException.badRequest("INVALID_ROLLOUT_VERSION",
                    "所选规则版本不存在或已停用");
        }
    }

    private void refreshRuleVersionPointers(String ruleId, RequestContext context) {
        List<Map<String, Object>> latestVersions = normalizeRows(ruleVersionLifecycleMapper.latestEnabledRuleVersion(ruleId));
        if (latestVersions.isEmpty()) {
            ruleVersionLifecycleMapper.markRuleAggregateDisabled(ruleId, context.actor(), timestamp(clock.instant()));
            return;
        }
        Map<String, Object> latest = latestVersions.get(0);
        long latestVersion = ((Number) latest.get("version")).longValue();
        String status = String.valueOf(latest.get("status"));
        ruleVersionLifecycleMapper.updateRuleVersionPointers(ruleId, latestVersion, status,
                context.actor(), timestamp(clock.instant()));
    }

    private long nextScopedVersion(String table, String keyColumn, String keyValue) {
        if (!"rule_version".equals(table) || !"rule_id".equals(keyColumn)) {
            throw new IllegalArgumentException("Unsupported scoped version: " + table + "." + keyColumn);
        }
        long maximum = platformCoreMapper.maxRuleVersion(keyValue);
        return nextCounter(table + ":" + keyValue, maximum + 1);
    }

    private long nextCounter(String counterKey, long initialValue) {
        int updated = platformCoreMapper.incrementScopedCounter(counterKey, timestamp(clock.instant()));
        if (updated == 0) {
            try {
                platformCoreMapper.insertScopedCounter(counterKey, initialValue, timestamp(clock.instant()));
                return initialValue;
            } catch (DuplicateKeyException ignored) {
                platformCoreMapper.incrementScopedCounter(counterKey, timestamp(clock.instant()));
            }
        }
        Long value = platformCoreMapper.scopedCounterValue(counterKey);
        if (value == null) {
            throw new IllegalStateException("Counter did not return a value: " + counterKey);
        }
        return value;
    }

    private Map<String, Object> getById(String table, String id) {
        Map<String, Object> row = platformCoreMapper.findById(table, id);
        if (row == null) {
            throw PlatformException.notFound(table, id);
        }
        return normalizeRow(row);
    }

    private void requireExists(String table, String id) {
        if (platformCoreMapper.countById(table, id) == 0) {
            throw PlatformException.notFound(table, id);
        }
    }

    private void validateEnvironment(String applicationId, String environmentId) {
        List<Map<String, Object>> rows = normalizeRows(
                platformCoreMapper.validateEnvironment(applicationId, environmentId));
        if (rows.isEmpty()) {
            throw PlatformException.badRequest("INVALID_ENVIRONMENT",
                    "所选环境不存在，或不属于当前应用");
        }
        String type = String.valueOf(rows.get(0).get("type")).toLowerCase(Locale.ROOT);
        if (!List.of("dev", "sit", "uat", "prod").contains(type)) {
            throw PlatformException.badRequest("INVALID_ENVIRONMENT_TYPE",
                    "环境类型仅允许 dev、sit、uat、prod");
        }
    }

    private String latestSidecarId(String instanceId) {
        List<Map<String, Object>> rows = normalizeRows(platformCoreMapper.latestSidecar(instanceId));
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("id"));
    }

    private String resolveEnvironmentId(String applicationId, String environmentId, String environmentName) {
        if (environmentId != null) {
            validateEnvironment(applicationId, environmentId);
            return environmentId;
        }
        if (environmentName == null || environmentName.isBlank()) {
            return null;
        }
        String normalized = environmentName.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = normalizeRows(platformCoreMapper.environmentByName(applicationId, normalized));
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("id"));
    }

    private String uniqueInstanceNickname(String preferred, String fallback, Instant registeredAt) {
        String base = normalizeNickname(preferred == null || preferred.isBlank() ? fallback : preferred);
        if (!instanceNicknameExists(base)) {
            return base;
        }
        String timestamped = appendNicknameSuffix(base, String.valueOf(registeredAt.toEpochMilli()));
        if (!instanceNicknameExists(timestamped)) {
            return timestamped;
        }
        for (int index = 2; index < 1000; index++) {
            String candidate = appendNicknameSuffix(timestamped, String.valueOf(index));
            if (!instanceNicknameExists(candidate)) {
                return candidate;
            }
        }
        throw PlatformException.conflict("INSTANCE_NICKNAME_CONFLICT",
                "无法生成全局唯一的实例昵称，请显式提供 nickname", Map.of("nickname", base));
    }

    private boolean instanceNicknameExists(String nickname) {
        return platformCoreMapper.countInstanceNickname(nickname) > 0;
    }

    private String appendNicknameSuffix(String base, String suffix) {
        String effectiveSuffix = "-" + suffix;
        int maxBaseLength = Math.max(1, 255 - effectiveSuffix.length());
        return base.substring(0, Math.min(base.length(), maxBaseLength)) + effectiveSuffix;
    }

    private String normalizeNickname(String nickname) {
        String normalized = nickname == null ? "" : nickname.trim();
        if (normalized.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: nickname");
        }
        if (normalized.length() > 255) {
            normalized = normalized.substring(0, 255);
        }
        return normalized;
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

    private long count(String table) {
        return platformCoreMapper.count(table);
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
            case "rule", "rule_version" -> "RULE_MANAGE";
            case "agent_instance", "sidecar_instance" -> "AGENT_MANAGE";
            default -> "ADMIN";
        };
    }

    private void recordAudit(RequestContext context, String action, String resourceType, String resourceId,
                                long resourceVersion, String beforeHash, String afterHash, String result,
                                String reason, Map<String, Object> details) {
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

        platformCoreMapper.insertAudit(auditId, timestamp(now), context.actor(), context.identitySource(), action,
                resourceType, resourceId, resourceVersion, beforeHash, afterHash, previousHash, recordHash,
                context.correlationId(), context.ipAddress(), context.device(), result, reason, json(details));

    }

    private String previousAuditHash() {
        List<String> hashes = platformCoreMapper.auditHashes();
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

    private Map<String, Object> single(List<Map<String, Object>> rows, String resourceType, String resourceId) {
        if (rows.isEmpty()) {
            throw PlatformException.notFound(resourceType, resourceId);
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> optionalRow(Map<String, Object> row) {
        return row == null ? List.of() : List.of(row);
    }

    private String requiredString(Map<String, Object> request, String key, String defaultValue) {
        String value = optionalString(request, key, defaultValue);
        if (value == null || value.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        return value;
    }

    private String ruleLifecycleStatus(Map<String, Object> request, String key, String defaultValue) {
        String value = optionalString(request, key, defaultValue);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("DISABLED".equals(normalized)) {
            throw PlatformException.badRequest("INVALID_RULE_STATUS",
                    "规则或规则版本停用必须通过停用接口处理，不能在创建时直接写入停用状态");
        }
        if ("ENABLED".equals(normalized)) {
            return "ENABLED";
        }
        throw PlatformException.badRequest("INVALID_RULE_STATUS",
                "创建规则或规则版本时状态只允许 ENABLED，停用必须通过停用接口处理");
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
