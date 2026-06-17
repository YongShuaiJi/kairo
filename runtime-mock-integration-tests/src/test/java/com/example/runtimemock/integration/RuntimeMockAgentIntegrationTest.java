package com.example.runtimemock.integration;

import com.example.demo.BizException;
import com.example.demo.CreateOrderRequest;
import com.example.demo.Order;
import com.example.demo.OrderService;
import com.example.runtimemock.agent.core.AgentRuntime;
import com.example.runtimemock.agent.server.AgentHttpServer;
import com.example.runtimemock.api.InvokePhase;
import com.example.runtimemock.api.MethodSelector;
import com.example.runtimemock.api.MockRule;
import com.example.runtimemock.core.ClassLoaderIdentity;
import com.example.runtimemock.core.CompiledRule;
import com.example.runtimemock.core.MethodDescriptor;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeMockAgentIntegrationTest {

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
    void concurrentCallsUseIsolatedScriptInstancesAndRespectMaxHits() throws Exception {
        Method method = OrderService.class.getMethod("calculateScore", int.class);
        runtime.publish(method, rule("max-hits", method, InvokePhase.BEFORE, """
                return mock.returnValue(999)
                """).toBuilder().maxHits(10).build());

        var executor = Executors.newFixedThreadPool(100);
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
}
