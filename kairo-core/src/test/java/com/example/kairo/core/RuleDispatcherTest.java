package com.example.kairo.core;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.MockRule;
import com.example.kairo.bridge.BridgeAction;
import com.example.kairo.bridge.EnterResult;
import com.example.kairo.groovy.CompiledMockScript;
import com.example.kairo.object.DefaultRuntimeObjectFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RuleDispatcherTest {

    private final RuleRegistry ruleRegistry = new RuleRegistry();
    private final LimitedScriptLog log = new LimitedScriptLog();
    private final java.time.Clock clock = java.time.Clock.systemUTC();
    private RuleDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
    void scriptErrorFailsOpenAndRecordsError() throws Exception {
        CompiledRule rule = register("error-rule", method(), new StubScript(() -> {
            throw new IllegalStateException("boom");
        }));
        dispatcher = newDispatcher(RuleDispatcherConfig.defaults());

        EnterResult result = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});

        assertThat(result.getAction()).isEqualTo(BridgeAction.PROCEED);
        assertThat(result.isSkipOriginalMethod()).isFalse();
        assertThat(rule.errors()).isEqualTo(1);
        assertThat(rule.locked()).isFalse();
        assertThat(log.snapshot().stream().anyMatch(msg -> msg.contains("failed; fail-open"))).isTrue();
    }

    @Test
    void consecutiveErrorsCircuitBreakAndFailOpen() throws Exception {
        CompiledRule rule = register("consecutive-rule", method(),
                new StubScript(() -> {
                    throw new IllegalStateException("boom");
                }), 3);
        dispatcher = newDispatcher(RuleDispatcherConfig.defaults());

        for (int i = 0; i < 3; i++) {
            EnterResult result = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
            assertThat(result.getAction()).isEqualTo(BridgeAction.PROCEED);
        }

        assertThat(rule.errors()).isEqualTo(3);
        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.CONSECUTIVE_ERRORS);
    }

    @Test
    void timeoutCircuitBreaksAndFailsOpen() throws Exception {
        StubScript slowScript = new StubScript(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return MockDecision.proceed();
        });
        CompiledRule rule = register("slow-rule", method(), slowScript);
        RuleDispatcherConfig config = RuleDispatcherConfig.builder()
                .scriptTimeoutMillis(50L)
                .firstScriptTimeoutMillis(50L)
                .executorMaxPoolSize(2)
                .build();
        dispatcher = newDispatcher(config);

        EnterResult result = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});

        assertThat(result.getAction()).isEqualTo(BridgeAction.PROCEED);
        assertThat(result.isSkipOriginalMethod()).isFalse();
        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.TIMEOUT);
        assertThat(rule.unfinishedTaskCount()).isGreaterThanOrEqualTo(1);
        assertThat(log.snapshot().stream().anyMatch(msg -> msg.contains("timeout") && msg.contains("fail-open")))
                .isTrue();
    }

    @Test
    void circuitBrokenRuleSkipsSubsequentHitsWithoutRunningScript() throws Exception {
        AtomicInteger scriptRuns = new AtomicInteger();
        StubScript script = new StubScript(() -> {
            scriptRuns.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        CompiledRule rule = register("breaking-rule", method(), script, 2);
        dispatcher = newDispatcher(RuleDispatcherConfig.defaults());

        // Two errors trip the circuit (threshold 2); each dispatch fails open.
        dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
        dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
        assertThat(rule.locked()).isTrue();
        assertThat(rule.circuitBreakReason()).isEqualTo(CircuitBreakReason.CONSECUTIVE_ERRORS);
        int runsBefore = scriptRuns.get();
        assertThat(rule.errors()).isEqualTo(2);

        // A subsequent hit must be skipped entirely: the script does not run, no further error
        // accrues, and the original method proceeds (fail-open, not fail-closed).
        EnterResult after = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
        assertThat(after.getAction()).isEqualTo(BridgeAction.PROCEED);
        assertThat(after.isSkipOriginalMethod()).isFalse();
        assertThat(scriptRuns.get()).as("script must not run once the circuit is open").isEqualTo(runsBefore);
        assertThat(rule.errors()).as("errors must not accrue once the circuit is open").isEqualTo(2);
    }

    @Test
    void completesUnderLongTimeoutWithoutCircuitBreak() throws Exception {
        StubScript quickScript = new StubScript(() -> {
            Thread.sleep(30);
            return MockDecision.proceed();
        });
        CompiledRule rule = register("quick-rule", method(), quickScript);
        RuleDispatcherConfig config = RuleDispatcherConfig.builder()
                .scriptTimeoutMillis(5_000L)
                .firstScriptTimeoutMillis(5_000L)
                .executorMaxPoolSize(2)
                .build();
        dispatcher = newDispatcher(config);

        EnterResult result = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});

        assertThat(result.getAction()).isEqualTo(BridgeAction.PROCEED);
        assertThat(rule.locked()).isFalse();
        assertThat(rule.circuitBreakReason()).isNull();
        assertThat(rule.lastDurationMillis()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void executorSaturationFailsOpenAndCircuitBreaks() throws Exception {
        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        StubScript blockingScript = new StubScript(() -> {
            blockingStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return MockDecision.proceed();
        });
        StubScript fastScript = new StubScript(() -> MockDecision.proceed());

        CompiledRule blockingRule = register("blocking-rule", method(), blockingScript);
        CompiledRule fastRule = register("fast-rule", secondMethod(), fastScript);

        RuleDispatcherConfig config = RuleDispatcherConfig.builder()
                .scriptTimeoutMillis(30_000L)
                .firstScriptTimeoutMillis(30_000L)
                .executorCorePoolSize(0)
                .executorMaxPoolSize(1)
                .executorQueueCapacity(0)
                .build();
        dispatcher = newDispatcher(config);

        ExecutorService driver = Executors.newFixedThreadPool(2);
        try {
            Future<EnterResult> blockingFuture = driver.submit(() ->
                    dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[0]));
            assertThatNoException().isThrownBy(blockingStarted::await);

            // The single worker thread is now pinned by the blocking script; a second concurrent
            // dispatch must be rejected by the synchronous-queue executor.
            EnterResult rejected = dispatcher.onEnter(secondMethodKey(), secondMethodMetadata(), null, new Object[0]);

            assertThat(rejected.getAction()).isEqualTo(BridgeAction.PROCEED);
            assertThat(rejected.isSkipOriginalMethod()).isFalse();
            assertThat(fastRule.locked()).isTrue();
            assertThat(fastRule.circuitBreakReason()).isEqualTo(CircuitBreakReason.SATURATION);
            assertThat(log.snapshot().stream().anyMatch(msg -> msg.contains("saturation") && msg.contains("fail-open")))
                    .isTrue();

            releaseBlocker.countDown();
            EnterResult blockingResult = blockingFuture.get(10, TimeUnit.SECONDS);
            assertThat(blockingResult.getAction()).isEqualTo(BridgeAction.PROCEED);
            assertThat(blockingRule.locked()).isFalse();
        } finally {
            driver.shutdownNow();
        }
    }

    @Test
    void closeShutsDownExecutor() {
        dispatcher = newDispatcher(RuleDispatcherConfig.builder()
                .executorMaxPoolSize(1)
                .build());

        assertThatNoException().isThrownBy(dispatcher::close);
        dispatcher = null;
    }

    private RuleDispatcher newDispatcher(RuleDispatcherConfig config) {
        return new RuleDispatcher(ruleRegistry, new DefaultRuntimeObjectFactory(),
                new DecisionValidator(), new ReentryGuard(), new SamplingPolicy(), log, clock, config);
    }

    private CompiledRule register(String id, Method method, CompiledMockScript script) {
        return register(id, method, script, 3);
    }

    private CompiledRule register(String id, Method method, CompiledMockScript script, int threshold) {
        MockRule rule = baseRule(id, method).consecutiveFailureThreshold(threshold).build();
        CompiledRule compiledRule = new CompiledRule(rule, script);
        ruleRegistry.addRule(MethodKey.of(method), compiledRule);
        return compiledRule;
    }

    private static MockRule.Builder baseRule(String id, Method method) {
        return MockRule.builder()
                .id(id)
                .target(new MethodSelector(method.getDeclaringClass().getName(),
                        ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                        method.getName(),
                        MethodDescriptor.of(method)))
                .phase(InvokePhase.BEFORE)
                .script("return null");
    }

    private static Method method() {
        try {
            return Target.class.getMethod("echo", String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodKey methodKey() {
        return MethodKey.of(method());
    }

    private static MethodMetadata methodMetadata() {
        return new MethodMetadata(method(), MethodDescriptor.of(method()));
    }

    private static Method secondMethod() {
        try {
            return Target.class.getMethod("second", int.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodKey secondMethodKey() {
        return MethodKey.of(secondMethod());
    }

    private static MethodMetadata secondMethodMetadata() {
        return new MethodMetadata(secondMethod(), MethodDescriptor.of(secondMethod()));
    }

    public static final class Target {
        public String echo(String value) {
            return value;
        }

        public int second(int value) {
            return value;
        }
    }

    /** Script whose body is supplied by the test, run on the dispatch executor. */
    private static final class StubScript implements CompiledMockScript {
        private final Body body;

        interface Body {
            MockDecision run() throws Exception;
        }

        StubScript(Body body) {
            this.body = body;
        }

        @Override
        public String ruleId() {
            return "stub";
        }

        @Override
        public long version() {
            return 1;
        }

        @Override
        public String scriptHash() {
            return "stub-hash";
        }

        @Override
        public MockDecision execute(InvocationContext context) {
            try {
                MockDecision decision = body.run();
                return decision == null ? MockDecision.proceed() : decision;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
