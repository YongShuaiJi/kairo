package com.example.kairo.integration;

import com.example.demo.BizException;
import com.example.demo.CreateOrderRequest;
import com.example.demo.Order;
import com.example.demo.OrderService;
import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.agent.core.RecordedInvocation;
import com.example.kairo.agent.core.RuleInfo;
import com.example.kairo.agent.server.AgentHttpServer;
import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.api.ScriptSessionSpec;
import com.example.kairo.api.ScriptSessionStatus;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.MethodDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KairoAgentIntegrationTest {

    private AgentRuntime runtime;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void beforeRuleCanModifyArguments() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("before-args", method, InvokePhase.BEFORE, """
                mock.set(args[0], 'amount', new java.math.BigDecimal('1'))
                return mock.proceed()
                """));

        OrderService service = new OrderService();
        Order order = service.createOrder(new CreateOrderRequest("U100", new BigDecimal("10000")));

        assertThat(order.getStatus()).isEqualTo("SUCCESS");
        assertThat(order.getAmount()).isEqualByComparingTo("1");
        assertThat(service.createOrderInvocationCount()).isEqualTo(1);
    }

    @Test
    void beforeRuleCanReplaceWholeArgumentObject() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("before-replace-arg", method, InvokePhase.BEFORE, """
                def request = mock.fromJson('{"userId":"U200","amount":2}', method.parameterTypes[0])
                def newArgs = args.clone()
                newArgs[0] = request
                return mock.proceed(newArgs)
                """));

        Order order = new OrderService().createOrder(new CreateOrderRequest("U100", new BigDecimal("10000")));

        assertThat(order.getAmount()).isEqualByComparingTo("2");
    }

    @Test
    void beforeRuleCanReturnAndSkipOriginalMethod() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("before-return", method, InvokePhase.BEFORE, """
                return mock.returnJson('{"id":"MOCK-001","status":"MOCKED","amount":1,"message":"early"}')
                """));

        OrderService service = new OrderService();
        Order order = service.createOrder(new CreateOrderRequest("U100", new BigDecimal("10000")));

        assertThat(order.getId()).isEqualTo("MOCK-001");
        assertThat(order.getStatus()).isEqualTo("MOCKED");
        assertThat(service.createOrderInvocationCount()).isZero();
    }

    @Test
    void beforeRuleCanThrowAndSkipOriginalMethod() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("before-throw", method, InvokePhase.BEFORE, """
                return mock.throwException('com.example.demo.BizException', 'mock denied')
                """));

        OrderService service = new OrderService();

        assertThatThrownBy(() -> service.createOrder(new CreateOrderRequest("U100", BigDecimal.ONE)))
                .isInstanceOf(BizException.class)
                .hasMessage("mock denied");
        assertThat(service.createOrderInvocationCount()).isZero();
    }

    @Test
    void returnRuleCanModifyReturnObject() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("return-modify", method, InvokePhase.RETURN, """
                result.status = 'REVIEW_REQUIRED'
                result.message = 'changed by return rule'
                return mock.returnValue(result)
                """));

        OrderService service = new OrderService();
        Order order = service.createOrder(new CreateOrderRequest("U100", BigDecimal.ONE));

        assertThat(order.getStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(order.getMessage()).isEqualTo("changed by return rule");
        assertThat(service.createOrderInvocationCount()).isEqualTo(1);
    }

    @Test
    void returnRuleCanReplaceWholeReturnObject() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("return-replace", method, InvokePhase.RETURN, """
                return mock.returnJson('{"id":"REPLACED","status":"REPLACED","amount":3,"message":"new object"}')
                """));

        Order order = new OrderService().createOrder(new CreateOrderRequest("U100", BigDecimal.ONE));

        assertThat(order.getId()).isEqualTo("REPLACED");
        assertThat(order.getStatus()).isEqualTo("REPLACED");
        assertThat(order.getAmount()).isEqualByComparingTo("3");
    }

    @Test
    void returnRuleCanConvertReturnToException() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("return-throw", method, InvokePhase.RETURN, """
                return mock.throwException('com.example.demo.BizException', 'post check failed')
                """));

        OrderService service = new OrderService();

        assertThatThrownBy(() -> service.createOrder(new CreateOrderRequest("U100", BigDecimal.ONE)))
                .isInstanceOf(BizException.class)
                .hasMessage("post check failed");
        assertThat(service.createOrderInvocationCount()).isEqualTo(1);
    }

    @Test
    void throwsRuleCanConvertExceptionToReturnValue() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("throws-return", method, InvokePhase.THROWS, """
                return mock.returnJson('{"id":"DEGRADED","status":"DEGRADED","amount":0,"message":"fallback"}')
                """));

        OrderService service = new OrderService();
        Order order = service.createOrder(new CreateOrderRequest("U100", new BigDecimal("10000")));

        assertThat(order.getId()).isEqualTo("DEGRADED");
        assertThat(order.getStatus()).isEqualTo("DEGRADED");
        assertThat(service.createOrderInvocationCount()).isEqualTo(1);
    }

    @Test
    void throwsRuleCanReplaceException() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("throws-replace", method, InvokePhase.THROWS, """
                return mock.throwException('com.example.demo.BizException', 'replaced')
                """));

        OrderService service = new OrderService();

        assertThatThrownBy(() -> service.createOrder(new CreateOrderRequest("U100", new BigDecimal("10000"))))
                .isInstanceOf(BizException.class)
                .hasMessage("replaced");
    }

    @Test
    void primitiveArgumentsAndReturnValuesAreSupported() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.publish(method, rule("primitive-before", method, InvokePhase.BEFORE, """
                def newArgs = args.clone()
                newArgs[0] = 5
                return mock.proceed(newArgs)
                """));
        runtime.publish(method, rule("primitive-return", method, InvokePhase.RETURN, """
                return mock.returnValue(result + 1)
                """));

        assertThat(new OrderService().calculateScore(100)).isEqualTo(11);
    }

    @Test
    void primitiveEarlyReturnAndNullReturnValidationFailOpen() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.publish(method, rule("primitive-early", method, InvokePhase.BEFORE, """
                return mock.returnValue(77)
                """));

        assertThat(new OrderService().calculateScore(100)).isEqualTo(77);

        runtime.publish(method, rule("primitive-null", method, InvokePhase.BEFORE, """
                return mock.returnValue(null)
                """).toBuilder().priority(200).build());

        assertThat(new OrderService().calculateScore(5)).isEqualTo(10);
    }

    @Test
    void voidMethodsCanBeSkippedAndThrownExceptionsCanBeSwallowed() throws Exception {
        Method method = OrderService.class.getMethod("sendNotification", String.class);
        runtime.publish(method, rule("void-skip", method, InvokePhase.BEFORE, """
                if (args[0] == 'skip') {
                    return mock.returnValue(null)
                }
                return mock.proceed()
                """));
        runtime.publish(method, rule("void-swallow", method, InvokePhase.THROWS, """
                return mock.returnValue(null)
                """));

        OrderService service = new OrderService();
        service.sendNotification("skip");
        assertThat(service.notificationInvocationCount()).isZero();

        assertThatNoException().isThrownBy(() -> service.sendNotification("boom"));
        assertThat(service.notificationInvocationCount()).isEqualTo(1);
    }

    @Test
    void voidMethodCanThrowAndRejectNonNullReturnValue() throws Exception {
        Method method = OrderService.class.getMethod("sendNotification", String.class);
        runtime.publish(method, rule("void-throw", method, InvokePhase.BEFORE, """
                if (args[0] == 'throw') {
                    return mock.throwException('com.example.demo.BizException', 'void denied')
                }
                if (args[0] == 'bad-return') {
                    return mock.returnValue('not allowed')
                }
                return mock.proceed()
                """));

        OrderService service = new OrderService();
        assertThatThrownBy(() -> service.sendNotification("throw"))
                .isInstanceOf(BizException.class)
                .hasMessage("void denied");

        assertThatNoException().isThrownBy(() -> service.sendNotification("bad-return"));
        assertThat(service.notificationInvocationCount()).isEqualTo(1);
    }

    @Test
    void staticMethodsAndOverloadsAreMatchedByDescriptor() throws Exception {
        Method staticMethod = OrderService.class.getMethod("staticMethod", String.class);
        runtime.publish(staticMethod, rule("static-before", staticMethod, InvokePhase.BEFORE, """
                def newArgs = args.clone()
                newArgs[0] = 'mock'
                return mock.proceed(newArgs)
                """));

        Method overloadString = OrderService.class.getMethod("overload", String.class);
        runtime.publish(overloadString, rule("overload-string", overloadString, InvokePhase.RETURN, """
                return mock.returnValue('mocked-string')
                """));

        OrderService service = new OrderService();

        assertThat(OrderService.staticMethod("origin")).isEqualTo("origin-mock");
        assertThat(service.overload("x")).isEqualTo("mocked-string");
        assertThat(service.overload(7)).isEqualTo("int-7");
    }

    @Test
    void privateMethodsCanBeEnhanced() throws Exception {
        Method method = OrderService.class.getDeclaredMethod("privateEcho", String.class);
        runtime.publish(method, rule("private-return", method, InvokePhase.RETURN, """
                return mock.returnValue('private-mocked')
                """));

        assertThat(new OrderService().callPrivateEcho("x")).isEqualTo("private-mocked");
    }

    @Test
    void scriptErrorsFailOpenAndRecordError() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        CompiledRule compiledRule = runtime.publish(method, rule("bad-script", method, InvokePhase.BEFORE, """
                return missing.property
                """));

        OrderService service = new OrderService();
        Order order = service.createOrder(new CreateOrderRequest("U100", BigDecimal.ONE));

        assertThat(order.getStatus()).isEqualTo("SUCCESS");
        assertThat(compiledRule.errors()).isEqualTo(1);
    }

    @Test
    void slowScriptTimesOutCircuitBreaksAndFailsOpen() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        CompiledRule compiledRule = runtime.publish(method, rule("slow-script", method,
                InvokePhase.BEFORE, """
                        Thread.sleep(3_000)
                        return mock.returnValue(999)
                        """).toBuilder()
                .capabilityProfile(com.example.kairo.api.CapabilityProfile.UNRESTRICTED)
                .build());

        // First-run timeout is 1s (RuleDispatcherConfig.defaults); the 3s sleep must trip it,
        // circuit-break the rule with TIMEOUT, and fail open so the original method runs.
        int result = new OrderService().calculateScore(7);

        assertThat(result).isEqualTo(14);
        assertThat(compiledRule.locked()).isTrue();
        assertThat(compiledRule.circuitBreakReason())
                .isEqualTo(com.example.kairo.core.CircuitBreakReason.TIMEOUT);
        assertThat(compiledRule.unfinishedTaskCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentCallsUseIsolatedScriptInstancesAndRespectMaxHits() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.publish(method, rule("max-hits", method, InvokePhase.BEFORE, """
                return mock.returnValue(999)
                """).toBuilder().maxHits(10).build());

        // Keep more than one caller to exercise the atomic maxHits claim, but do not
        // conflate this test with dispatcher saturation. GitHub's 2-core runners expose
        // only four default script workers, so a 100-thread burst legitimately fail-opens
        // most calls before the rule executes.
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int value = i;
                tasks.add(() -> new OrderService().calculateScore(value));
            }
            List<Future<Integer>> futures = executor.invokeAll(tasks);
            long mocked = 0;
            for (Future<Integer> future : futures) {
                if (future.get() == 999) {
                    mocked++;
                }
            }
            assertThat(mocked).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }
    }

    // -------------------------------------------------------- ScriptSession (V1.2 phase 3)

    @Test
    void trialSessionAppliesAndInterceptsLiveMethod() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        ScriptSessionSpec spec = sessionSpec("trial-live", method,
                "return mock.returnValue(999)", CapabilityProfile.SAFE, 60_000L, 10L);

        runtime.scriptSessionManager().create(spec);
        assertThat(runtime.scriptSessionManager().validate("trial-live").status())
                .isEqualTo(ScriptSessionStatus.VALIDATED);
        ScriptSessionResult applied = runtime.scriptSessionManager().apply("trial-live");
        assertThat(applied.status()).isEqualTo(ScriptSessionStatus.APPLIED);

        // The trial rule is live on the real JVM: the original calculateScore(7)=14 is replaced.
        assertThat(new OrderService().calculateScore(7)).isEqualTo(999);
        ScriptSessionResult result = runtime.scriptSessionManager().result("trial-live");
        assertThat(result.hitCount()).isEqualTo(1);
        // The trial rule is published on the live method like a formal rule.
        assertThat(runtime.rules().stream().map(RuleInfo::id).toList()).contains("trial-live");
    }

    @Test
    void trialSessionExpiresByTtlAndRestoresOriginalBehavior() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.scriptSessionManager().create(sessionSpec("trial-ttl", method,
                "return mock.returnValue(999)", CapabilityProfile.SAFE, 1_000L, 10L));
        runtime.scriptSessionManager().validate("trial-ttl");
        runtime.scriptSessionManager().apply("trial-ttl");
        assertThat(new OrderService().calculateScore(7)).isEqualTo(999);

        // The agent's local deadline drives expiry; no Platform or client is involved. Poll the
        // snapshot (which lazily expires) until the session reaches a terminal state.
        ScriptSessionResult result = waitForTerminal("trial-ttl", 5_000L);
        assertThat(result.status()).isEqualTo(ScriptSessionStatus.EXPIRED);

        // Once expired the trial rule is removed and the original behavior is restored, even
        // though nothing but the local clock swept it.
        assertThat(new OrderService().calculateScore(7)).isEqualTo(14);
    }

    @Test
    void trialSessionExpiresByHitCapAndRestoresOriginalBehavior() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.scriptSessionManager().create(sessionSpec("trial-hits", method,
                "return mock.returnValue(999)", CapabilityProfile.SAFE, 60_000L, 2L));
        runtime.scriptSessionManager().validate("trial-hits");
        runtime.scriptSessionManager().apply("trial-hits");

        OrderService service = new OrderService();
        assertThat(service.calculateScore(7)).isEqualTo(999);  // hit 1
        assertThat(service.calculateScore(7)).isEqualTo(999);  // hit 2, cap reached
        // The rule itself stops matching once the cap is hit, so the original method runs again
        // before the manager sweeps.
        assertThat(service.calculateScore(7)).isEqualTo(14);

        assertThat(runtime.scriptSessionManager().result("trial-hits").status())
                .isEqualTo(ScriptSessionStatus.EXPIRED);
    }

    @Test
    void promoteToFormalRuleSurvivesSessionRevertWithSameScope() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.scriptSessionManager().create(sessionSpec("trial-promote", method,
                "return mock.returnValue(999)", CapabilityProfile.EXTENDED, 1_000L, 5L));
        runtime.scriptSessionManager().validate("trial-promote");
        runtime.scriptSessionManager().apply("trial-promote");
        assertThat(new OrderService().calculateScore(7)).isEqualTo(999);

        runtime.scriptSessionManager().promote("trial-promote", "operator");

        // The formal rule (same id, unbounded) keeps intercepting...
        assertThat(new OrderService().calculateScore(7)).isEqualTo(999);
        // ...and reverting the now-terminal session must NOT delete the formal rule.
        runtime.scriptSessionManager().revert("trial-promote");
        assertThat(new OrderService().calculateScore(7)).isEqualTo(999);

        // The formal rule is a normal published rule now, traced to its session origin, with the
        // session's original capability profile (promotion does not widen permissions/scope).
        RuleInfo formal = runtime.rules().stream()
                .filter(r -> "trial-promote".equals(r.id()))
                .findFirst()
                .orElseThrow();
        assertThat(formal.enabled()).isTrue();
        // Remove via the normal rule path to clean up.
        runtime.remove("trial-promote", "test");
        assertThat(new OrderService().calculateScore(7)).isEqualTo(14);
    }

    @Test
    void emergencyDeactivateTargetRevertsTrialAndRestoresBehavior() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.scriptSessionManager().create(sessionSpec("trial-panic", method,
                "return mock.returnValue(999)", CapabilityProfile.SAFE, 60_000L, 10L));
        runtime.scriptSessionManager().validate("trial-panic");
        runtime.scriptSessionManager().apply("trial-panic");
        assertThat(new OrderService().calculateScore(7)).isEqualTo(999);

        int deactivated = runtime.scriptSessionManager()
                .deactivateTarget(OrderService.class.getName(), "operator");
        assertThat(deactivated).isEqualTo(1);
        assertThat(runtime.scriptSessionManager().result("trial-panic").status())
                .isEqualTo(ScriptSessionStatus.REVERTED);
        assertThat(new OrderService().calculateScore(7)).isEqualTo(14);
    }

    private ScriptSessionResult waitForTerminal(String sessionId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        ScriptSessionResult result = runtime.scriptSessionManager().result(sessionId);
        while (!result.status().terminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
            result = runtime.scriptSessionManager().result(sessionId);
        }
        return result;
    }

    private static ScriptSessionSpec sessionSpec(String sessionId, Method method, String script,
                                                 CapabilityProfile profile, long ttlMillis, long maxHits) {
        return new ScriptSessionSpec(sessionId, "agent-1",
                new MethodSelector(method.getDeclaringClass().getName(),
                        ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                        method.getName(), MethodDescriptor.of(method)),
                script, profile, new ScriptPolicyRevision(1, "test"), ttlMillis, maxHits, "tester");
    }

    @Test
    void updatingRuleScriptDoesNotRetransformAgain() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.publish(method, rule("update-me", method, InvokePhase.BEFORE, """
                return mock.returnValue(1)
                """));
        long afterFirstPublish = runtime.transformerManager().retransformCount();

        runtime.publish(method, rule("update-me", method, InvokePhase.BEFORE, """
                return mock.returnValue(2)
                """).toBuilder().version(2).build());

        assertThat(runtime.transformerManager().retransformCount()).isEqualTo(afterFirstPublish);
        assertThat(new OrderService().calculateScore(10)).isEqualTo(2);
    }

    @Test
    void firstRuleAndLastRuleTriggerRetransformation() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        long before = runtime.transformerManager().retransformCount();
        runtime.publish(method, rule("retransform", method, InvokePhase.BEFORE, """
                return mock.returnValue(1)
                """));
        assertThat(runtime.transformerManager().retransformCount()).isEqualTo(before + 1);

        runtime.remove(method, "retransform");
        assertThat(runtime.transformerManager().retransformCount()).isEqualTo(before + 2);
    }

    @Test
    void recordingSessionInstrumentsMethodWithoutMockRuleAndCapturesOutcome() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        List<RecordedInvocation> recorded = new CopyOnWriteArrayList<>();
        runtime.recordingSink(recorded::add);

        runtime.startRecording(
                "recording-integration",
                method.getDeclaringClass().getName(),
                method.getName(),
                MethodDescriptor.of(method),
                "test"
        );

        assertThat(new OrderService().calculateScore(7)).isEqualTo(14);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).sessionId()).isEqualTo("recording-integration");
        assertThat(recorded.get(0).arguments()).containsExactly(7);
        assertThat(recorded.get(0).result()).isEqualTo(14);
        assertThat(recorded.get(0).metadata())
                .containsEntry("className", OrderService.class.getName())
                .containsEntry("methodName", "calculateScore");

        runtime.stopRecording("recording-integration", "test");
        assertThat(new OrderService().calculateScore(8)).isEqualTo(16);
        assertThat(recorded).hasSize(1);
    }

    @Test
    void resetAllRemovesRulesAndRestoresOriginalBehavior() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.publish(method, rule("reset-me", method, InvokePhase.BEFORE, """
                return mock.returnValue(123)
                """));

        assertThat(new OrderService().calculateScore(1)).isEqualTo(123);

        runtime.resetAll("test");

        assertThat(new OrderService().calculateScore(1)).isEqualTo(2);
        assertThat(runtime.rules()).isEmpty();
    }

    @Test
    void sameClassNameInDifferentClassLoadersIsMatchedByClassLoader() throws Exception {
        Class<?> firstClass = compileAndLoadDuplicateService("first");
        Class<?> secondClass = compileAndLoadDuplicateService("second");
        Object first = firstClass.getDeclaredConstructor().newInstance();
        Object second = secondClass.getDeclaredConstructor().newInstance();
        Method firstEcho = firstClass.getMethod("echo", String.class);
        Method secondEcho = secondClass.getMethod("echo", String.class);

        runtime.publish(firstEcho, rule("loader-specific", firstEcho, InvokePhase.RETURN, """
                return mock.returnValue('mocked')
                """));

        assertThat(firstEcho.invoke(first, "x")).isEqualTo("mocked");
        assertThat(secondEcho.invoke(second, "x")).isEqualTo("second-x");
    }

    @Test
    void agentHttpApiCanPublishRuleAndExposeHealthMetricsAndEvents() throws Exception {
        try (AgentHttpServer server = new AgentHttpServer(runtime, "127.0.0.1", 0, "dev-token")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);

            HttpResponse<String> v1Health = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(v1Health.statusCode()).isEqualTo(200);

            HttpResponse<String> console = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(console.statusCode()).isEqualTo(200);
            assertThat(console.headers().firstValue("Content-Type")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("text/html"));
            assertThat(console.body()).contains("Kairo Console");

            HttpResponse<String> consoleScript = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/app.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(consoleScript.statusCode()).isEqualTo(200);
            assertThat(consoleScript.body()).contains("Authorization", "/v1");

            String classId = runtime.loadedClassRepository().classId(OrderService.class);
            Method method = OrderService.class.getMethod("calculateScore", int.class);
            String json = """
                    {
                      "id": "http-rule",
                      "name": "http-rule",
                      "classId": "%s",
                      "className": "%s",
                      "classLoaderId": "%s",
                      "methodName": "%s",
                      "methodDescriptor": "%s",
                      "phase": "BEFORE",
                      "script": "return mock.returnValue(44)",
                      "priority": 100,
                      "percentage": 100,
                      "failOpen": true,
                      "enabled": true
                    }
                    """.formatted(classId, method.getDeclaringClass().getName(),
                    ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                    method.getName(), MethodDescriptor.of(method));

            HttpResponse<String> publish = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/rules"))
                            .header("X-Agent-Token", "dev-token")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(publish.statusCode()).isEqualTo(201);
            assertThat(new OrderService().calculateScore(2)).isEqualTo(44);

            HttpResponse<String> status = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/status"))
                            .header("X-Agent-Token", "dev-token")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.body()).contains("protocolVersion");

            HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/metrics"))
                            .header("X-Agent-Token", "dev-token")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode metricsJson = objectMapper.readTree(metrics.body());
            assertThat(metricsJson.path("activeRuleCount").asInt()).isEqualTo(1);

            HttpResponse<String> events = client.send(HttpRequest.newBuilder(URI.create(base + "/events"))
                            .header("X-Agent-Token", "dev-token")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(events.body()).contains("rule.create");

            HttpResponse<String> resetClass = client.send(HttpRequest.newBuilder(URI.create(base + "/v1/agent/reset-class"))
                            .header("X-Agent-Token", "dev-token")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"classId\":\"" + classId + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resetClass.statusCode()).isEqualTo(200);
            assertThat(new OrderService().calculateScore(2)).isEqualTo(4);
        }
    }

    @Test
    void removingLastRuleRestoresOriginalBehavior() throws Exception {
        Method method = OrderService.class.getMethod("createOrder", CreateOrderRequest.class);
        runtime.publish(method, rule("remove-me", method, InvokePhase.BEFORE, """
                return mock.returnJson('{"id":"MOCK-001","status":"MOCKED","amount":1,"message":"early"}')
                """));

        OrderService first = new OrderService();
        assertThat(first.createOrder(new CreateOrderRequest("U100", new BigDecimal("10000"))).getStatus())
                .isEqualTo("MOCKED");

        runtime.remove(method, "remove-me");

        OrderService second = new OrderService();
        assertThatThrownBy(() -> second.createOrder(new CreateOrderRequest("U100", new BigDecimal("10000"))))
                .isInstanceOf(BizException.class)
                .hasMessage("amount too large");
    }

    @Test
    void syntheticAndBridgeMethodsCannotBePublished() {
        Method bridgeMethod = java.util.Arrays.stream(StringValue.class.getDeclaredMethods())
                .filter(Method::isBridge)
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> runtime.publish(bridgeMethod,
                rule("bridge-method", bridgeMethod, InvokePhase.RETURN,
                        "return mock.returnValue('blocked')")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Synthetic and bridge methods cannot be mocked");
    }

    // V1.5 §4.4: a pending rule pre-registered against a fuzzy selector materializes against the
    // actual loaded class on the next poll, recording the real ClassIdentity and taking effect.
    @Test
    void pendingRuleMaterializesOnFirstLoadAgainstActualClass() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        MockRule pending = rule("pending-score", method, InvokePhase.RETURN,
                "return mock.returnValue(999)");
        com.example.kairo.api.ClassSelector selector = com.example.kairo.api.ClassSelector.builder()
                .className(OrderService.class.getName())
                .build();
        runtime.registerPendingRule(selector, pending, "alice");
        assertThat(runtime.pendingRegistry().pendingCount()).isEqualTo(1);

        int materialized = runtime.pollPendingMatches();
        assertThat(materialized).isEqualTo(1);
        // The pending entry is consumed and the resolved identity is audited.
        assertThat(runtime.pendingRegistry().pendingCount()).isZero();
        assertThat(runtime.pendingRegistry().resolved()).hasSize(1);
        assertThat(runtime.pendingRegistry().resolved().get(0).actualIdentity().binaryClassName())
                .isEqualTo(OrderService.class.getName());
        // The materialized rule takes effect on the real class.
        assertThat(new OrderService().calculateScore(10)).isEqualTo(999);
    }

    // V1.5 §4.4: after a rule is applied, a changed bytecode hash reconciles to DRIFTED and maps
    // to TARGET_DRIFTED so the apply chain fails open instead of re-enhancing a drifted target.
    @Test
    void hotUpdateDriftIsDetectedAfterApply() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.publish(method, rule("drift-score", method, InvokePhase.RETURN,
                "return mock.returnValue(1)"));
        // A simulated external redefine produces a different current input hash.
        com.example.kairo.agent.core.HotUpdateReconciler.Result drifted =
                runtime.checkHotUpdateDrift(method.getDeclaringClass(), "a-different-bytecode-hash");
        assertThat(drifted.isDrifted()).isTrue();
        assertThat(drifted.previousHash()).isNotEqualTo("a-different-bytecode-hash");
        assertThat(runtime.hotUpdateReconciler().toStatus(drifted))
                .isEqualTo(com.example.kairo.api.ApplyChainStatus.TARGET_DRIFTED);
        // An unchanged hash stays compatible (re-apply at a new revision).
        com.example.kairo.agent.core.HotUpdateReconciler.Result same =
                runtime.checkHotUpdateDrift(method.getDeclaringClass(), drifted.previousHash());
        assertThat(same.isDrifted()).isFalse();
    }

    // V1.5 §4.4: a pending rule registered for a not-yet-loaded class materializes via the
    // first-load AgentBuilder discovery hook (the InputCaptureTransformer observer), not only the
    // 2s poll. Loading a fresh class after registration must apply the rule within a window far
    // shorter than the poll interval.
    @Test
    void firstLoadObserverMaterializesPendingRuleImmediately() throws Exception {
        String simple = "ObserverTarget" + System.nanoTime();
        String name = "com.example.observer." + simple;
        com.example.kairo.api.MockRule pending = com.example.kairo.api.MockRule.builder()
                .id("observer-rule").name("observer-rule")
                .target(com.example.kairo.api.MethodSelector.builder()
                        .className(name).methodName("score").methodDescriptor("(I)I").build())
                .phase(InvokePhase.BEFORE).script("return mock.returnValue(999)")
                .priority(100).percentage(100).failOpen(true).enabled(true)
                .build();
        com.example.kairo.api.ClassSelector selector =
                com.example.kairo.api.ClassSelector.builder().className(name).build();
        runtime.registerPendingRule(selector, pending, "tester");
        assertThat(runtime.pendingRegistry().pendingCount()).isEqualTo(1);

        // Load the class AFTER registering pending -> the first-load observer fires (class-load
        // thread) and hands materialization to the cleanup executor (no class-load-thread reentry).
        Class<?> type = compileAndLoad(name, """
                package com.example.observer;
                public class %s {
                    public int score(int x) { return x * 2; }
                }
                """.formatted(simple));
        Object instance = type.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method score = type.getMethod("score", int.class);

        // The observer materializes well within the 2s poll interval; allow a generous window
        // for the retransform to apply under suite load.
        long deadline = System.currentTimeMillis() + 4000;
        int result = -1;
        while (System.currentTimeMillis() < deadline) {
            result = (Integer) score.invoke(instance, 5);
            if (result == 999) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(result).isEqualTo(999);
        assertThat(runtime.pendingRegistry().pendingCount()).isZero();
        assertThat(runtime.pendingRegistry().resolved()).hasSize(1);
        assertThat(runtime.pendingRegistry().resolved().get(0).actualIdentity().binaryClassName())
                .isEqualTo(name);
    }

    // V1.5 §4.4: an external redefine of a class Kairo has applied to is detected automatically by
    // the redefine listener (input-capture -> reconciler -> driftedClasses), without an explicit
    // checkHotUpdateDrift call. A genuine JVM redefine with different method-body bytes.
    @Test
    void realRedefineFlagsDriftAutomatically() throws Exception {
        String simple = "RedefineTarget" + System.nanoTime();
        String name = "com.example.redefine." + simple;
        Class<?> type = compileAndLoad(name, """
                package com.example.redefine;
                public class %s {
                    public int score(int x) { return x * 2; }
                }
                """.formatted(simple));
        java.lang.reflect.Method method = type.getMethod("score", int.class);
        runtime.publish(method, rule("redefine-rule", method, InvokePhase.BEFORE,
                "return mock.returnValue(999)"));
        assertThat((Integer) method.invoke(type.getDeclaredConstructor().newInstance(), 5)).isEqualTo(999);

        // Externally redefine the class with a different method body.
        byte[] redefined = compileToBytes(name, """
                package com.example.redefine;
                public class %s {
                    public int score(int x) { return x * 3; }
                }
                """.formatted(simple));
        runtime.instrumentation()
                .redefineClasses(new java.lang.instrument.ClassDefinition(type, redefined));

        // The redefine listener flags drift asynchronously.
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !runtime.isClassDrifted(name)) {
            Thread.sleep(50);
        }
        assertThat(runtime.isClassDrifted(name))
                .as("external redefine must flag drift automatically").isTrue();
        // A drifted class resolves to DRIFTED so the platform surfaces TARGET_DRIFTED.
        com.example.kairo.api.MethodSelector ms = new com.example.kairo.api.MethodSelector(
                name, com.example.kairo.core.ClassLoaderIdentity.idOf(type.getClassLoader()),
                "score", "(I)I");
        com.example.kairo.api.TargetMatchResult result = runtime.resolveTarget(type,
                com.example.kairo.api.EnhancementTarget.of(ms, com.example.kairo.api.EnhancementLocation.METHOD_ENTER));
        assertThat(result.status()).isEqualTo(com.example.kairo.api.TargetMatchResult.Status.DRIFTED);

        // A hot update must fail closed before it mutates the registry or retransforms the class.
        // Previously publishLocked accepted this update and recordAppliedHash silently cleared the
        // drift marker, allowing an unsafe overwrite even though the historical drift event stayed
        // visible. Pin both the exact status token and the absence of mutation.
        String scriptHashBeforeRejectedUpdate = runtime.rules().stream()
                .filter(r -> "redefine-rule".equals(r.id()))
                .findFirst().orElseThrow().scriptHash();
        assertThatThrownBy(() -> runtime.publish(method,
                rule("redefine-rule", method, InvokePhase.BEFORE,
                        "return mock.returnValue(123)")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TARGET_DRIFTED")
                .hasMessageContaining(name);
        assertThat(runtime.isClassDrifted(name)).isTrue();
        assertThat(runtime.rules().stream()
                .filter(r -> "redefine-rule".equals(r.id()))
                .findFirst().orElseThrow().scriptHash())
                .isEqualTo(scriptHashBeforeRejectedUpdate);
        assertThat((Integer) method.invoke(type.getDeclaredConstructor().newInstance(), 5))
                .isNotEqualTo(123);
    }

    // V1.5 §4.1: the ClassLoader tree the Web selector renders. A class loaded by a custom
    // URLClassLoader appears in liveLoaders and the parent->children tree.
    @Test
    void loaderTreeExposesCustomClassLoader() throws Exception {
        String simple = "LoaderTreeTarget" + System.nanoTime();
        String name = "com.example.loader." + simple;
        Class<?> type = compileAndLoad(name, """
                package com.example.loader;
                public class %s {
                    public String echo(String s) { return s; }
                }
                """.formatted(simple));
        runtime.loadedClassRepository().toClassInfo(type);
        java.util.List<com.example.kairo.agent.core.LoaderInfo> loaders =
                runtime.classLoaderRepository().liveLoaders();
        assertThat(loaders).anyMatch(l -> "java.net.URLClassLoader".equals(l.className()));
        assertThat(runtime.classLoaderRepository().loaderTree()).isNotEmpty();
    }

    private Class<?> compileAndLoad(String binaryName, String source) throws Exception {
        Path dir = Files.createTempDirectory("kairo-it-" + binaryName.replace('.', '-'));
        Path src = dir.resolve(binaryName.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(null, null, null, "-d", dir.toString(), src.toString())).isZero();
        URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                getClass().getClassLoader());
        return Class.forName(binaryName, true, loader);
    }

    private byte[] compileToBytes(String binaryName, String source) throws Exception {
        Path dir = Files.createTempDirectory("kairo-it-bytes-" + binaryName.replace('.', '-'));
        Path src = dir.resolve(binaryName.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(null, null, null, "-d", dir.toString(), src.toString())).isZero();
        return Files.readAllBytes(dir.resolve(binaryName.replace('.', '/') + ".class"));
    }

    private Class<?> compileAndLoadDuplicateService(String prefix) throws Exception {
        Path dir = Files.createTempDirectory("duplicate-service-" + prefix);
        Path sourceDir = dir.resolve("com/example/duplicate");
        Files.createDirectories(sourceDir);
        Path source = sourceDir.resolve("DuplicateService.java");
        Files.writeString(source, """
                package com.example.duplicate;
                public class DuplicateService {
                    public String echo(String value) {
                        return "%s-" + value;
                    }
                }
                """.formatted(prefix));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(null, null, null, "-d", dir.toString(), source.toString())).isZero();
        URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, ClassLoader.getSystemClassLoader());
        return Class.forName("com.example.duplicate.DuplicateService", true, loader);
    }

    private static MockRule rule(String id, Method method, InvokePhase phase, String script) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .phase(phase)
                .script(script)
                .priority(100)
                .percentage(100)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    private interface Value<T> {
        T value();
    }

    private static final class StringValue implements Value<String> {
        @Override
        public String value() {
            return "value";
        }
    }
}
