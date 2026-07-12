package com.example.kairo.core;

import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.MockRule;
import com.example.kairo.bridge.BridgeAction;
import com.example.kairo.bridge.EnterResult;
import com.example.kairo.bridge.ExitResult;
import com.example.kairo.groovy.CompiledMockScript;
import com.example.kairo.object.DefaultRuntimeObjectFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.4 rule-chain composition and propagation semantics: argument threading,
 * return/throw replacement and recovery, FINALLY observe-only, fail-open,
 * proceed-original, and the one-snapshot-per-invocation guarantee.
 */
class RuleChainDispatcherTest {

    private final RuleRegistry ruleRegistry = new RuleRegistry();
    private final LimitedScriptLog log = new LimitedScriptLog();
    private RuleDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
    void beforeChainsArgumentsThroughEachProceedRule() throws Exception {
        register("a", method(), EnhancementLocation.METHOD_ENTER, 10, ctx ->
                MockDecision.proceed(new Object[]{ctx.arguments()[0] + "-A"}));
        register("b", method(), EnhancementLocation.METHOD_ENTER, 5, ctx ->
                MockDecision.proceed(new Object[]{ctx.arguments()[0] + "-B"}));
        dispatcher = newDispatcher();

        EnterResult result = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});

        assertThat(result.getAction()).isEqualTo(BridgeAction.PROCEED);
        assertThat(result.getArguments()[0]).isEqualTo("x-A-B");
    }

    @Test
    void returnValueTerminatesBeforeSubsequentRules() throws Exception {
        AtomicReference<String> ran = new AtomicReference<>("none");
        register("a", method(), EnhancementLocation.METHOD_ENTER, 10, ctx -> MockDecision.returnValue("短路"));
        register("b", method(), EnhancementLocation.METHOD_ENTER, 5, ctx -> {
            ran.set("b-ran");
            return MockDecision.proceed();
        });
        dispatcher = newDispatcher();

        EnterResult result = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});

        assertThat(result.getAction()).isEqualTo(BridgeAction.RETURN);
        assertThat(result.getReturnValue()).isEqualTo("短路");
        assertThat(result.isSkipOriginalMethod()).isTrue();
        assertThat(ran.get()).isEqualTo("none");
    }

    @Test
    void returnSideReplacementContinuesAndIsObservable() throws Exception {
        register("a", method(), EnhancementLocation.METHOD_RETURN, 10,
                ctx -> MockDecision.replaceReturnValue(ctx.result() + "+A"));
        register("b", method(), EnhancementLocation.METHOD_RETURN, 5,
                ctx -> MockDecision.replaceReturnValue(ctx.result() + "+B"));
        dispatcher = newDispatcher();

        EnterResult enter = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
        ExitResult exit = dispatcher.onExit((InvocationState) enter.getInvocationToken(), "orig", null);

        assertThat(exit.getAction()).isEqualTo(BridgeAction.RETURN);
        assertThat(exit.getReturnValue()).isEqualTo("orig+A+B");
    }

    @Test
    void throwReplacementAndRecoveryToReturn() throws Exception {
        // The THROWS chain passes the exception down; a rule may replace it or recover to a return.
        register("a", method(), EnhancementLocation.METHOD_THROW, 10,
                ctx -> MockDecision.replaceThrowable(new IllegalStateException("replaced-" + ctx.throwable().getMessage())));
        register("b", method(), EnhancementLocation.METHOD_THROW, 5,
                ctx -> MockDecision.replaceReturnValue("recovered"));
        dispatcher = newDispatcher();

        EnterResult enter = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
        ExitResult exit = dispatcher.onExit((InvocationState) enter.getInvocationToken(),
                null, new RuntimeException("boom"));

        // The throw rule replaced the exception; a later throw rule recovered it to a return value.
        assertThat(exit.getAction()).isEqualTo(BridgeAction.RETURN);
        assertThat(exit.getReturnValue()).isEqualTo("recovered");
    }

    @Test
    void finallyObservesFinalOutcomeWithoutMutating() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>();
        register("a", method(), EnhancementLocation.METHOD_RETURN, 10,
                ctx -> MockDecision.returnValue("final"));
        register("f", method(), EnhancementLocation.METHOD_FINALLY, 0,
                ctx -> {
                    observed.set(ctx.outcomeState() + ":" + ctx.result());
                    return MockDecision.returnValue("ignored");
                });
        dispatcher = newDispatcher();

        EnterResult enter = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
        ExitResult exit = dispatcher.onExit((InvocationState) enter.getInvocationToken(), "orig", null);

        assertThat(exit.getReturnValue()).isEqualTo("final");
        assertThat(observed.get()).isEqualTo("RETURNING:final");
    }

    @Test
    void scriptErrorFailsOpenAndChainContinues() throws Exception {
        register("a", method(), EnhancementLocation.METHOD_RETURN, 10, ctx -> {
            throw new IllegalStateException("boom");
        });
        register("b", method(), EnhancementLocation.METHOD_RETURN, 5,
                ctx -> MockDecision.returnValue("from-b"));
        dispatcher = newDispatcher();

        EnterResult enter = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
        ExitResult exit = dispatcher.onExit((InvocationState) enter.getInvocationToken(), "orig", null);

        assertThat(exit.getReturnValue()).isEqualTo("from-b");
    }

    @Test
    void proceedOriginalStopsEnterChainAndRunsBody() throws Exception {
        AtomicReference<String> ran = new AtomicReference<>("none");
        register("a", method(), EnhancementLocation.METHOD_ENTER, 10,
                ctx -> MockDecision.proceedOriginal());
        register("b", method(), EnhancementLocation.METHOD_ENTER, 5, ctx -> {
            ran.set("b-ran");
            return MockDecision.proceed();
        });
        dispatcher = newDispatcher();

        EnterResult result = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});

        assertThat(result.getAction()).isEqualTo(BridgeAction.PROCEED);
        assertThat(result.isSkipOriginalMethod()).isFalse();
        assertThat(ran.get()).isEqualTo("none");
    }

    @Test
    void invocationUsesFrozenSnapshotDespiteMidInvocationPublish() throws Exception {
        register("a", method(), EnhancementLocation.METHOD_ENTER, 10, ctx -> MockDecision.proceed());
        register("r1", method(), EnhancementLocation.METHOD_RETURN, 10,
                ctx -> MockDecision.returnValue("r1-result"));
        dispatcher = newDispatcher();

        EnterResult enter = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});

        // Mid-invocation: replace the return rule with a different one.
        ruleRegistry.removeRule(methodKey(), "r1");
        register("r2", method(), EnhancementLocation.METHOD_RETURN, 10,
                ctx -> MockDecision.returnValue("r2-result"));

        ExitResult exit = dispatcher.onExit((InvocationState) enter.getInvocationToken(), "orig", null);

        // The exit must use the snapshot frozen at enter (r1), not the newly-published r2.
        assertThat(exit.getReturnValue()).isEqualTo("r1-result");
    }

    @Test
    void originalAndCurrentAreBothReadable() throws Exception {
        register("a", method(), EnhancementLocation.METHOD_RETURN, 10,
                ctx -> MockDecision.replaceReturnValue(ctx.originalResult() + "->" + ctx.result()));
        dispatcher = newDispatcher();

        EnterResult enter = dispatcher.onEnter(methodKey(), methodMetadata(), null, new Object[]{"x"});
        // first rule sees original == current == "orig"
        ExitResult exit = dispatcher.onExit((InvocationState) enter.getInvocationToken(), "orig", null);

        assertThat(exit.getReturnValue()).isEqualTo("orig->orig");
    }

    // -------------------------------------------------------- helpers

    private RuleDispatcher newDispatcher() {
        return new RuleDispatcher(ruleRegistry, new DefaultRuntimeObjectFactory(),
                new DecisionValidator(), new ReentryGuard(), new SamplingPolicy(), log,
                java.time.Clock.systemUTC(), RuleDispatcherConfig.defaults());
    }

    private CompiledRule register(String id, Method method, EnhancementLocation location, int priority,
                                  ScriptBody body) {
        MockRule rule = MockRule.builder()
                .id(id)
                .target(new MethodSelector(method.getDeclaringClass().getName(),
                        ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()),
                        method.getName(), MethodDescriptor.of(method)))
                .location(location)
                .phase(InvokePhase.BEFORE)
                .priority(priority)
                .script("return null")
                .build();
        CompiledRule compiled = new CompiledRule(rule, new BodyScript(id, body));
        ruleRegistry.addRule(MethodKey.of(method), compiled);
        return compiled;
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

    @FunctionalInterface
    private interface ScriptBody {
        MockDecision run(InvocationContext ctx) throws Exception;
    }

    private static final class BodyScript implements CompiledMockScript {
        private final String id;
        private final ScriptBody body;

        BodyScript(String id, ScriptBody body) {
            this.id = id;
            this.body = body;
        }

        @Override public String ruleId() { return id; }
        @Override public long version() { return 1; }
        @Override public String scriptHash() { return "hash-" + id; }

        @Override
        public MockDecision execute(InvocationContext context) {
            try {
                MockDecision d = body.run(context);
                return d == null ? MockDecision.proceed() : d;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static final class Target {
        public String echo(String value) {
            return value;
        }
    }
}
