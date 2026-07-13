package com.example.kairo.platform;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.EnhancementTargetResolutionService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.RequestContext;
import com.example.kairo.platform.service.TargetDiscoveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.3 §3.5 backend: persistence of enhancement location + call-site selector, legacy mapping,
 * save-time target resolution + drift validation, and the constructor / call-site discovery API.
 * The agent side is simulated by polling and acking the commands the platform dispatches, mirroring
 * {@link ScriptSessionLifecycleIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class EnhancementLocationPlatformIntegrationTest {

    @Autowired PlatformCoreService coreService;
    @Autowired AgentCommandService commands;
    @Autowired TargetDiscoveryService targetDiscoveryService;
    @Autowired EnhancementTargetResolutionService resolutionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    private static final AtomicLong COUNTER = new AtomicLong();

    private String instanceId;
    private String agentId;
    private RequestContext admin;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        long n = COUNTER.incrementAndGet();
        instanceId = "instance-loc-" + n;
        agentId = "agent-loc-" + n;
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values (?, 'app-default', 'env-dev', 'loc', 'localhost', '1', 'java',
                  'ACTIVE', '{}', current_timestamp, current_timestamp)
                """, instanceId);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
        admin = new RequestContext("system", "corr", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr", "127.0.0.1", "agent", "test");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from agent_command where agent_id = ?", agentId);
        jdbc.update("delete from rule_target where rule_version_id in (select rv.id from rule_version rv join rule r on r.id = rv.rule_id where r.created_by = ?)", admin.actor());
        jdbc.update("delete from rule_capability where rule_version_id in (select rv.id from rule_version rv join rule r on r.id = rv.rule_id where r.created_by = ?)", admin.actor());
        jdbc.update("delete from rule_version where rule_id in (select id from rule where created_by = ?)", admin.actor());
        jdbc.update("delete from rule where created_by = ?", admin.actor());
        jdbc.update("delete from agent_instance where id = ?", agentId);
        jdbc.update("delete from instance where id = ?", instanceId);
    }

    // -------------------------------------------------------- persistence + legacy mapping

    @Test
    void legacyRuleWithoutLocationSavesWithoutResolution() {
        Map<String, Object> request = baseMethodRule("Legacy phase rule", "query", "()V", null, null);
        // No location -> no save-time resolution -> no agent command dispatched.
        Map<String, Object> rule = coreService.createRule(admin, request);
        String ruleId = String.valueOf(rule.get("id"));
        Map<String, Object> target = ruleTargetRow(ruleId);
        assertThat(target.get("LOCATION")).isNull();
        assertThat(target.get("CALL_SITE_SELECTOR_JSON")).isNull();
        Map<String, Object> matcher = readJson(String.valueOf(target.get("MATCHER_JSON")));
        assertThat(matcher).doesNotContainKey("location");
        assertThat(commandsDispatched()).isZero();
    }

    @Test
    void constructorRuleResolvesAndPersistsLocation() {
        Map<String, Object> request = baseMethodRule("Constructor rule", "<init>", "()V",
                "CONSTRUCTOR_AFTER_SUPER", null);
        Map<String, Object> rule = runWithAck(() -> coreService.createRule(admin, request),
                Map.of("status", "MATCHED", "matchedCount", 1, "risk", "LOW"));
        String ruleId = String.valueOf(rule.get("id"));
        Map<String, Object> target = ruleTargetRow(ruleId);
        assertThat(target.get("LOCATION")).isEqualTo("CONSTRUCTOR_AFTER_SUPER");
        assertThat(target.get("CALL_SITE_SELECTOR_JSON")).isNull();
        Map<String, Object> matcher = readJson(String.valueOf(target.get("MATCHER_JSON")));
        assertThat(matcher.get("location")).isEqualTo("CONSTRUCTOR_AFTER_SUPER");
        assertThat(commandsDispatched()).isEqualTo(1);
    }

    @Test
    void callSiteRuleResolvesAndStampsFreshFingerprint() {
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("owner", "com.example.Callee");
        selector.put("name", "run");
        selector.put("descriptor", "()V");
        selector.put("opcode", "INVOKEVIRTUAL");
        selector.put("occurrenceIndex", 1);
        Map<String, Object> request = baseMethodRule("Call-site rule", "invoke", "()V",
                "CALL_RETURN", selector);
        Map<String, Object> resolvedIdentity = new LinkedHashMap<>(selector);
        resolvedIdentity.put("fingerprint", "fp-fresh-abc");
        Map<String, Object> rule = runWithAck(() -> coreService.createRule(admin, request),
                Map.of("status", "MATCHED", "matchedCount", 1, "risk", "MEDIUM",
                        "occurrenceCount", 3, "occurrenceIndex", 1, "resolvedIdentity", resolvedIdentity));
        String ruleId = String.valueOf(rule.get("id"));
        Map<String, Object> target = ruleTargetRow(ruleId);
        assertThat(target.get("LOCATION")).isEqualTo("CALL_RETURN");
        Map<String, Object> persisted = readJson(String.valueOf(target.get("CALL_SITE_SELECTOR_JSON")));
        assertThat(persisted.get("fingerprint")).isEqualTo("fp-fresh-abc");
        assertThat(persisted.get("occurrenceIndex")).isEqualTo(1);
    }

    // -------------------------------------------------------- save-time drift / rejection

    @Test
    void callSiteRuleRejectsDriftedTarget() {
        Map<String, Object> selector = callSiteSelector(0);
        Map<String, Object> request = baseMethodRule("Drift rule", "invoke", "()V", "CALL_RETURN", selector);
        assertThatThrownBy(() -> runWithAck(() -> coreService.createRule(admin, request),
                Map.of("status", "DRIFTED", "reason", "fingerprint changed", "matchedCount", 1)))
                .isInstanceOfSatisfying(PlatformException.class,
                        e -> { assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo("TARGET_DRIFTED"); });
        assertThat(jdbc.queryForObject("select count(*) from rule where name = 'Drift rule'", Integer.class)).isZero();
    }

    // V1.5 §4.1/§5: a name that resolves to more than one ClassLoader must surface as
    // AMBIGUOUS_TARGET (409) with the candidate loader ids, never a silent first-match weave.
    @Test
    void callSiteRuleRejectsAmbiguousTarget() {
        Map<String, Object> selector = callSiteSelector(0);
        Map<String, Object> request = baseMethodRule("Ambiguous rule", "invoke", "()V", "CALL_RETURN", selector);
        assertThatThrownBy(() -> runWithAck(() -> coreService.createRule(admin, request),
                Map.of("status", "AMBIGUOUS", "reason", "matched 2 loaders", "matchedCount", 2,
                        "candidateLoaderIds", java.util.List.of("loader-A", "loader-B"))))
                .isInstanceOfSatisfying(PlatformException.class,
                        e -> { assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo("AMBIGUOUS_TARGET"); });
        assertThat(jdbc.queryForObject("select count(*) from rule where name = 'Ambiguous rule'", Integer.class)).isZero();
    }

    @Test
    void constructorRuleRejectsNativeTarget() {
        Map<String, Object> request = baseMethodRule("Native ctor rule", "<init>", "()V",
                "CONSTRUCTOR_AFTER_SUPER", null);
        assertThatThrownBy(() -> runWithAck(() -> coreService.createRule(admin, request),
                Map.of("status", "REJECTED", "reason", "native method")))
                .isInstanceOfSatisfying(PlatformException.class,
                        e -> { assertThat(e.status()).isEqualTo(400); assertThat(e.code()).isEqualTo("TARGET_REJECTED"); });
        assertThat(jdbc.queryForObject("select count(*) from rule where name = 'Native ctor rule'", Integer.class)).isZero();
    }

    @Test
    void callSiteRuleRejectsWhenNoAgentOnline() {
        jdbc.update("update agent_instance set status = 'OFFLINE' where id = ?", agentId);
        Map<String, Object> selector = callSiteSelector(0);
        Map<String, Object> request = baseMethodRule("No-agent rule", "invoke", "()V", "CALL_RETURN", selector);
        // No agent online -> resolution refuses before dispatching any command (synchronous, no ack needed).
        assertThatThrownBy(() -> coreService.createRule(admin, request))
                .isInstanceOfSatisfying(PlatformException.class,
                        e -> { assertThat(e.status()).isEqualTo(409); assertThat(e.code()).isEqualTo("TARGET_RESOLUTION_UNAVAILABLE"); });
        assertThat(commandsDispatched()).isZero();
    }

    // -------------------------------------------------------- discovery API

    @Test
    void discoverySearchReturnsConstructorAndSpecialMethodMarkers() {
        Map<String, Object> ctor = memberTarget("<init>", "()V", "CONSTRUCTOR", true, false, true);
        Map<String, Object> method = memberTarget("query", "()Ljava/lang/String;", "METHOD", false, false, false);
        List<Map<String, Object>> result = runWithAck(
                () -> targetDiscoveryService.search(admin, "Target", "app-default", "env-dev"),
                Map.of("targets", List.of(ctor, method), "truncated", false));
        assertThat(result).hasSize(2);
        Map<String, Object> constructor = result.stream()
                .filter(item -> "<init>".equals(String.valueOf(item.get("methodName")))).findFirst().orElseThrow();
        assertThat(constructor.get("memberKind")).isEqualTo("CONSTRUCTOR");
        assertThat(constructor.get("constructor")).isEqualTo(true);
    }

    @Test
    void listCallSitesReturnsCandidatesFromAgent() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", "app-default");
        body.put("environmentId", "env-dev");
        body.put("classId", "com.example.Target");
        body.put("callerMethodName", "invoke");
        body.put("callerMethodDescriptor", "()V");
        Map<String, Object> candidate = Map.of("owner", "com.example.Callee", "name", "run",
                "descriptor", "()V", "opcode", "INVOKEVIRTUAL", "occurrenceIndex", 1, "fingerprint", "fp-1");
        Map<String, Object> result = runWithAck(
                () -> targetDiscoveryService.listCallSites(admin, "app-default", "env-dev", body),
                Map.of("candidates", List.of(candidate), "count", 1, "className", "com.example.Target"));
        assertThat(result.get("agentAvailable")).isEqualTo(true);
        assertThat(result.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("fingerprint")).isEqualTo("fp-1");
    }

    @Test
    void resolveTargetPreviewReturnsStatusRiskAndOccurrence() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("className", "com.example.Target");
        target.put("methodName", "invoke");
        target.put("matcher", Map.of("classId", "com.example.Target", "descriptor", "()V"));
        target.put("location", "CALL_RETURN");
        target.put("callSiteSelector", callSiteSelector(1));
        Map<String, Object> resolvedIdentity = new LinkedHashMap<>(callSiteSelector(1));
        resolvedIdentity.put("fingerprint", "fp-preview");
        Map<String, Object> result = runWithAck(
                () -> resolutionService.resolve(admin, "app-default", "env-dev", target),
                Map.of("status", "MATCHED", "matchedCount", 1, "risk", "MEDIUM",
                        "occurrenceCount", 3, "occurrenceIndex", 1, "resolvedIdentity", resolvedIdentity));
        assertThat(result.get("status")).isEqualTo("MATCHED");
        assertThat(result.get("risk")).isEqualTo("MEDIUM");
        assertThat(((Number) result.get("occurrenceCount")).intValue()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> baseMethodRule(String name, String methodName, String descriptor,
                                               String location, Map<String, Object> callSiteSelector) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("protocol", "JAVA_METHOD");
        target.put("className", "com.example.Target");
        target.put("methodName", methodName);
        target.put("matcher", Map.of("classId", "com.example.Target", "descriptor", descriptor));
        if (location != null) {
            target.put("location", location);
        }
        if (callSiteSelector != null) {
            target.put("callSiteSelector", callSiteSelector);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("applicationId", "app-default");
        request.put("environmentId", "env-dev");
        request.put("name", name);
        request.put("riskLevel", "LOW");
        request.put("script", Map.of("phase", "RETURN", "script", "return mock.proceed()"));
        request.put("governance", Map.of());
        request.put("targets", List.of(target));
        request.put("capabilities", List.of("EARLY_RETURN"));
        request.put("reason", "enhancement location test " + UUID.randomUUID());
        return request;
    }

    private Map<String, Object> callSiteSelector(int occurrenceIndex) {
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("owner", "com.example.Callee");
        selector.put("name", "run");
        selector.put("descriptor", "()V");
        selector.put("opcode", "INVOKEVIRTUAL");
        selector.put("occurrenceIndex", occurrenceIndex);
        return selector;
    }

    private Map<String, Object> memberTarget(String name, String descriptor, String memberKind,
                                             boolean constructor, boolean nativeMethod, boolean abstractMethod) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("classId", "com.example.Target");
        target.put("className", "com.example.Target");
        target.put("classLoaderId", "loader-1");
        target.put("classLoaderClassName", "app");
        target.put("modifiable", true);
        target.put("methodName", name);
        target.put("descriptor", descriptor);
        target.put("returnType", "void");
        target.put("parameterTypes", List.of());
        target.put("exceptionTypes", List.of());
        target.put("static", false);
        target.put("private", false);
        target.put("memberKind", memberKind);
        target.put("constructor", constructor);
        target.put("native", nativeMethod);
        target.put("abstract", abstractMethod);
        target.put("final", false);
        target.put("synchronized", false);
        target.put("synthetic", false);
        target.put("bridge", false);
        target.put("modifiers", 1);
        return target;
    }

    private Map<String, Object> ruleTargetRow(String ruleId) {
        return jdbc.queryForMap(
                "select location, call_site_selector_json, matcher_json from rule_target where rule_version_id = ?",
                ruleId + ":1");
    }

    private int commandsDispatched() {
        Integer count = jdbc.queryForObject("select count(*) from agent_command where agent_id = ?", Integer.class, agentId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> readJson(String json) {
        return com.example.kairo.platform.service.PlatformJson.readMap(json);
    }

    // V1.5 §4.1/§5: the loader-tree endpoint dispatches LIST_LOADERS to a live agent and returns
    // the parent->children tree so the Web class selector can disambiguate same-name classes.
    @Test
    void loaderTreeEndpointReturnsAgentLoaders() {
        Map<String, Object> bootstrap = Map.of("loaderId", "bootstrap", "loaderClassName", "bootstrap", "parentLoaderId", "");
        Map<String, Object> appLoader = Map.of("loaderId", "app-loader-id", "loaderClassName",
                "jdk.internal.loader.ClassLoaders$AppClassLoader", "parentLoaderId", "bootstrap");
        Map<String, Object> springLoader = Map.of("loaderId", "spring-loader-id", "loaderClassName",
                "org.springframework.boot.loader.launch.LaunchedURLClassLoader",
                "parentLoaderId", "app-loader-id", "frameworkLoader", "Spring Boot (LaunchedURLClassLoader)");
        Map<String, Object> ackResult = new LinkedHashMap<>();
        ackResult.put("loaders", List.of(bootstrap, appLoader, springLoader));
        ackResult.put("tree", Map.of(
                "bootstrap", List.of(appLoader),
                "app-loader-id", List.of(springLoader)));
        ackResult.put("count", 3);
        ackResult.put("bootstrapLoaderId", "bootstrap");

        Map<String, Object> result = runWithAck(
                () -> targetDiscoveryService.listLoaders(admin, "app-default", "env-dev"),
                ackResult);

        assertThat(result.get("agentAvailable")).isEqualTo(true);
        assertThat(String.valueOf(result.get("bootstrapLoaderId"))).isEqualTo("bootstrap");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> loaders = (List<Map<String, Object>>) result.get("loaders");
        assertThat(loaders).hasSize(3);
        assertThat(loaders).anyMatch(l -> "Spring Boot (LaunchedURLClassLoader)".equals(String.valueOf(l.get("frameworkLoader"))));
    }

    // V1.5 §5: DISCOVER_TARGETS metadata (proxyType / supportLevel / driftStatus / loaderClass /
    // frameworkLoader) flows through the search aggregate, and same-name classes across two
    // loaders are presented as two distinct targets (keyed by classLoaderId), never collapsed.
    @Test
    void searchEchoesTargetMetadataAndDisambiguatesByLoader() {
        Map<String, Object> targetA = new LinkedHashMap<>();
        targetA.put("className", "com.example.OrderService");
        targetA.put("methodName", "createOrder");
        targetA.put("descriptor", "(Lcom/example/Order;)V");
        targetA.put("classLoaderId", "loader-A");
        targetA.put("classLoaderClassName", "java.net.URLClassLoader");
        targetA.put("loaderClass", "java.net.URLClassLoader");
        targetA.put("proxyType", "PLAIN");
        targetA.put("supportLevel", "SUPPORTED");
        targetA.put("driftStatus", "FRESH");
        Map<String, Object> targetB = new LinkedHashMap<>();
        targetB.put("className", "com.example.OrderService");
        targetB.put("methodName", "createOrder");
        targetB.put("descriptor", "(Lcom/example/Order;)V");
        targetB.put("classLoaderId", "loader-B");
        targetB.put("classLoaderClassName", "org.springframework.boot.loader.launch.LaunchedURLClassLoader");
        targetB.put("loaderClass", "org.springframework.boot.loader.launch.LaunchedURLClassLoader");
        targetB.put("frameworkLoader", "Spring Boot (LaunchedURLClassLoader)");
        targetB.put("proxyType", "CGLIB");
        targetB.put("supportLevel", "LIMITED");
        targetB.put("driftStatus", "DRIFTED");
        Map<String, Object> ackResult = Map.of("targets", List.of(targetA, targetB), "truncated", false);

        List<Map<String, Object>> result = runWithAck(
                () -> targetDiscoveryService.search(admin, "OrderService", "app-default", "env-dev"),
                ackResult);

        // Two same-name classes in two loaders -> two distinct entries.
        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(row -> {
            assertThat(row.get("proxyType")).isIn("PLAIN", "CGLIB");
            assertThat(row.get("supportLevel")).isIn("SUPPORTED", "LIMITED");
            assertThat(row.get("driftStatus")).isIn("FRESH", "DRIFTED");
        });
        assertThat(result).anyMatch(r -> "DRIFTED".equals(String.valueOf(r.get("driftStatus"))));
        assertThat(result).anyMatch(r -> "loader-B".equals(String.valueOf(r.get("classLoaderId"))));
    }

    private <T> T runWithAck(Supplier<T> call, Map<String, Object> ackResult) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(call::get);
        simulateAgentAck(ackResult, "ACKED", null);
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new AssertionError(e);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void simulateAgentAck(Map<String, Object> ackResult, String ackStatus, String errorMessage) {
        Map<String, Object> polled = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> candidate = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 10));
            if (!"NO_COMMAND".equals(candidate.get("status"))) {
                polled = candidate;
                break;
            }
            sleepQuiet(25);
        }
        assertThat(polled).as("agent command was not dispatched within timeout").isNotNull();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", ackStatus);
        ack.put("result", ackResult);
        if (errorMessage != null) {
            ack.put("errorMessage", errorMessage);
        }
        commands.ack(String.valueOf(polled.get("id")), agentCtx, ack);
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
