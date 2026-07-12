package com.example.kairo.core;

import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.ScriptLog;
import com.example.kairo.bridge.EnterResult;
import com.example.kairo.bridge.ExitResult;
import com.example.kairo.object.RuntimeObjectFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Location-aware rule dispatcher.
 *
 * <p>V1.3 runs rules by authoritative {@link EnhancementLocation} while preserving
 * the V1.2 method-path behaviour exactly: a method invocation still runs
 * BEFORE-on-enter, RETURN/THROWS-on-exit and fail-open on validation error. New
 * locations add FINALLY (observe-only, runs on every method exit), constructor
 * locations (enter after super / return / throw) and call-site locations
 * (before / return / throw around a single invoke instruction).
 *
 * <p>The V1 {@code onEnter}/{@code onExit} entry points handle method locations;
 * the V2 {@code onEnter}/{@code onExit} overloads handle constructor and
 * call-site locations via an {@link InvocationState} prepared by the bridge
 * dispatcher from a {@link com.example.kairo.bridge.InvocationEnvelope}.
 */
public final class RuleDispatcher implements AutoCloseable {

    private final RuleRegistry ruleRegistry;
    private final RuntimeObjectFactory objectFactory;
    private final DecisionValidator validator;
    private final ReentryGuard reentryGuard;
    private final SamplingPolicy samplingPolicy;
    private final ScriptLog log;
    private final Clock clock;
    private final RuleDispatcherConfig config;
    private final ThreadPoolExecutor scriptExecutor;
    private volatile boolean enabled = true;

    public RuleDispatcher(RuleRegistry ruleRegistry, RuntimeObjectFactory objectFactory) {
        this(ruleRegistry, objectFactory, new DecisionValidator(), new ReentryGuard(),
                new SamplingPolicy(), new LimitedScriptLog(), Clock.systemUTC(),
                RuleDispatcherConfig.defaults());
    }

    public RuleDispatcher(RuleRegistry ruleRegistry, RuntimeObjectFactory objectFactory, DecisionValidator validator,
                          ReentryGuard reentryGuard, SamplingPolicy samplingPolicy, ScriptLog log, Clock clock) {
        this(ruleRegistry, objectFactory, validator, reentryGuard, samplingPolicy, log, clock,
                RuleDispatcherConfig.defaults());
    }

    public RuleDispatcher(RuleRegistry ruleRegistry, RuntimeObjectFactory objectFactory, DecisionValidator validator,
                          ReentryGuard reentryGuard, SamplingPolicy samplingPolicy, ScriptLog log, Clock clock,
                          RuleDispatcherConfig config) {
        this.ruleRegistry = Objects.requireNonNull(ruleRegistry, "ruleRegistry");
        this.objectFactory = Objects.requireNonNull(objectFactory, "objectFactory");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reentryGuard = Objects.requireNonNull(reentryGuard, "reentryGuard");
        this.samplingPolicy = Objects.requireNonNull(samplingPolicy, "samplingPolicy");
        this.log = log == null ? ScriptLog.NOOP : log;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        this.scriptExecutor = newExecutor(config);
    }

    private static ThreadPoolExecutor newExecutor(RuleDispatcherConfig config) {
        BlockingQueue<Runnable> queue = config.executorQueueCapacity() == 0
                ? new SynchronousQueue<>()
                : new LinkedBlockingQueue<>(config.executorQueueCapacity());
        return new ThreadPoolExecutor(
                config.executorCorePoolSize(),
                config.executorMaxPoolSize(),
                config.executorKeepAliveSeconds(),
                TimeUnit.SECONDS,
                queue,
                runnable -> {
                    Thread thread = new Thread(runnable, config.threadNamePrefix());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    // -------------------------------------------------------- V1 method path (BEFORE/RETURN/THROWS + FINALLY)

    public EnterResult onEnter(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments) {
        if (!enabled) {
            return EnterResult.proceedWithoutContext();
        }
        RuleSet ruleSet = ruleRegistry.rules(methodKey);
        if (ruleSet.isEmpty()) {
            return EnterResult.proceedWithoutContext();
        }
        InvocationState state = new InvocationState(methodKey, method, target,
                arguments == null ? new Object[0] : arguments);
        if (!ruleSet.hasLocation(EnhancementLocation.METHOD_ENTER)) {
            return EnterResult.proceed(state, state.arguments());
        }
        return enter(state, EnhancementLocation.METHOD_ENTER);
    }

    public ExitResult onExit(InvocationState state, Object returnValue, Throwable throwable) {
        if (!enabled || state == null) {
            return ExitResult.proceed();
        }
        Object effectiveReturn = returnValue;
        Throwable effectiveThrow = throwable;
        boolean terminal = false;
        MockDecision terminalDecision = null;

        if (state.hasBeforeTerminalDecision()) {
            terminalDecision = state.beforeTerminalDecision();
            terminal = true;
        } else {
            RuleSet ruleSet = ruleRegistry.rules(state.methodKey());
            if (!ruleSet.isEmpty()) {
                EnhancementLocation primary = primaryExitLocation(state, throwable);
                MockDecision decision = runRules(ruleSet.rules(primary), primary, state, returnValue, throwable);
                // Constructors cannot substitute the constructed object, so a RETURN
                // decision at a constructor location is ignored (observe-only).
                if (decision.type() != MockDecision.Type.PROCEED
                        && !(isConstructorState(state) && decision.type() == MockDecision.Type.RETURN)) {
                    terminalDecision = decision;
                    terminal = true;
                }
            }
        }

        if (terminal && terminalDecision.type() == MockDecision.Type.RETURN) {
            effectiveReturn = validateExitReturn(state, terminalDecision.returnValue(), throwable == null);
            effectiveThrow = null;
        } else if (terminal && terminalDecision.type() == MockDecision.Type.THROW) {
            effectiveThrow = validator.validateThrowable(state.method(), terminalDecision.throwable());
            effectiveReturn = null;
        }

        runFinally(state, effectiveReturn, effectiveThrow);

        if (!terminal) {
            return ExitResult.proceed();
        }
        if (terminalDecision.type() == MockDecision.Type.RETURN) {
            return ExitResult.returnValue(effectiveReturn);
        }
        return ExitResult.throwException(effectiveThrow);
    }

    // -------------------------------------------------------- V2 constructor / call-site path

    /**
     * Generic enter for a prepared state at an enter-side location
     * (METHOD_ENTER, CONSTRUCTOR_AFTER_SUPER, CALL_BEFORE). Used by both the V1
     * method path and the V2 bridge dispatcher.
     */
    public EnterResult enter(InvocationState state, EnhancementLocation location) {
        if (!enabled || state == null) {
            return EnterResult.proceedWithoutContext();
        }
        RuleSet ruleSet = ruleRegistry.rules(state.methodKey());
        if (ruleSet.isEmpty() || !ruleSet.hasLocation(location)) {
            return EnterResult.proceed(state, currentArgs(state));
        }
        MockDecision decision = runRules(ruleSet.rules(location), location, state, null, null);
        // CONSTRUCTOR_AFTER_SUPER is observe-only: the super call has already run
        // and the constructor body cannot be skipped or its result substituted, so
        // the script runs for side-effects only and the construct always proceeds.
        if (!mayShortCircuit(location)) {
            return EnterResult.proceed(state, currentArgs(state));
        }
        try {
            validateCurrentArgs(state);
            if (decision.type() == MockDecision.Type.RETURN && mayReturnAt(location)) {
                Object returnValue = validateEnterReturn(state, location, decision.returnValue());
                state.beforeTerminalDecision(MockDecision.returnValue(returnValue));
                return EnterResult.returnValue(state, currentArgs(state), returnValue);
            }
            if (decision.type() == MockDecision.Type.THROW) {
                Throwable throwable = validator.validateThrowable(state.method(), decision.throwable());
                state.beforeTerminalDecision(MockDecision.throwException(throwable));
                return EnterResult.throwException(state, currentArgs(state), throwable);
            }
            return EnterResult.proceed(state, currentArgs(state));
        } catch (Throwable e) {
            log.error(location + " validation failed; fail-open", e);
            return EnterResult.proceed(state, state.originalArguments());
        }
    }

    /**
     * Generic exit for a prepared state at a return/throw location, with FINALLY
     * for method locations. Used by the V2 bridge dispatcher.
     */
    public ExitResult exit(InvocationState state, Object returnValue, Throwable throwable, EnhancementLocation location) {
        // location here is the enter-side location the state was created with; the
        // primary exit location is derived from the outcome.
        return onExit(state, returnValue, throwable);
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    // -------------------------------------------------------- internals

    private EnhancementLocation primaryExitLocation(InvocationState state, Throwable throwable) {
        if (state.isCallSite()) {
            return throwable == null ? EnhancementLocation.CALL_RETURN : EnhancementLocation.CALL_THROW;
        }
        if (state.method() != null && state.method().isConstructor()) {
            return throwable == null ? EnhancementLocation.CONSTRUCTOR_RETURN : EnhancementLocation.CONSTRUCTOR_THROW;
        }
        return throwable == null ? EnhancementLocation.METHOD_RETURN : EnhancementLocation.METHOD_THROW;
    }

    private void runFinally(InvocationState state, Object returnValue, Throwable throwable) {
        if (state.isCallSite() || (state.method() != null && state.method().isConstructor())) {
            return;
        }
        RuleSet ruleSet = ruleRegistry.rules(state.methodKey());
        if (ruleSet.isEmpty() || !ruleSet.hasLocation(EnhancementLocation.METHOD_FINALLY)) {
            return;
        }
        // FINALLY observes the final outcome; its decisions are ignored.
        runRules(ruleSet.rules(EnhancementLocation.METHOD_FINALLY),
                EnhancementLocation.METHOD_FINALLY, state, returnValue, throwable);
    }

    private Object[] currentArgs(InvocationState state) {
        return state.isCallSite() ? state.callArguments() : state.arguments();
    }

    private void validateCurrentArgs(InvocationState state) {
        if (state.isCallSite()) {
            if (state.callArguments() != null) {
                state.callArguments(validator.validateCallArguments(
                        state.callSiteSelector(), state.callArguments(), loaderOf(state)));
            }
        } else {
            state.arguments(validator.validateArguments(state.method(), state.arguments()));
        }
    }

    private Object validateEnterReturn(InvocationState state, EnhancementLocation location, Object returnValue) {
        if (location == EnhancementLocation.CALL_BEFORE) {
            return validator.validateCallResult(state.callSiteSelector(), returnValue, loaderOf(state));
        }
        return validator.validateReturnValue(state.method(), returnValue);
    }

    private Object validateExitReturn(InvocationState state, Object returnValue, boolean wasNormalReturn) {
        if (state.isCallSite()) {
            return validator.validateCallResult(state.callSiteSelector(), returnValue, loaderOf(state));
        }
        return validator.validateReturnValue(state.method(), returnValue);
    }

    private boolean mayReturnAt(EnhancementLocation location) {
        // Constructors never substitute a return value: the object is already under
        // construction and substituting it is unsafe. Enter-side method/call-site
        // locations may short-circuit with a return value.
        return !location.isConstructorLocation();
    }

    /** Enter locations that may short-circuit (skip the body/call) with a return or throw. */
    private boolean mayShortCircuit(EnhancementLocation location) {
        return location == EnhancementLocation.METHOD_ENTER || location == EnhancementLocation.CALL_BEFORE;
    }

    private boolean isConstructorState(InvocationState state) {
        return state.method() != null && state.method().isConstructor();
    }

    private ClassLoader loaderOf(InvocationState state) {
        MethodMetadata method = state.method();
        return method == null ? null : method.targetClassLoader();
    }

    private MockDecision runRules(List<CompiledRule> rules, EnhancementLocation location, InvocationState state,
                                  Object returnValue, Throwable throwable) {
        MockDecision lastProceed = MockDecision.proceed();
        long now = clock.millis();
        boolean enterSide = location.isEnterLocation();
        boolean finallyLocation = location.isFinallyLocation();
        for (CompiledRule compiledRule : rules) {
            if (!compiledRule.isActive(now)
                    || !samplingPolicy.shouldRun(compiledRule.rule().percentage())
                    || !compiledRule.tryClaimHit()) {
                continue;
            }
            try (ReentryGuard.Scope scope = reentryGuard.enter(state.methodKey(), location, compiledRule.rule().id())) {
                if (!scope.entered()) {
                    continue;
                }
                DefaultInvocationContext context = buildContext(location, state, returnValue, throwable);
                long started = System.nanoTime();
                long timeoutMillis = compiledRule.hasExecuted()
                        ? config.scriptTimeoutMillis()
                        : config.firstScriptTimeoutMillis();
                Future<MockDecision> execution;
                try {
                    execution = scriptExecutor.submit(() -> compiledRule.script().execute(context));
                } catch (RejectedExecutionException saturated) {
                    compiledRule.circuitBreak(CircuitBreakReason.SATURATION);
                    log.error("Rule " + compiledRule.rule().id()
                            + " executor rejected the task; circuit-open (saturation); fail-open", saturated);
                    continue;
                }
                MockDecision decision;
                try {
                    decision = execution.get(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException timedOut) {
                    execution.cancel(true);
                    compiledRule.recordTimeout();
                    log.error("Rule " + compiledRule.rule().id() + " exceeded " + timeoutMillis
                            + "ms timeout; circuit-open (timeout); " + compiledRule.unfinishedTaskCount()
                            + " unfinished task(s); fail-open", timedOut);
                    continue;
                } catch (ExecutionException scriptFailure) {
                    compiledRule.recordError();
                    log.error(failOpenMessage(compiledRule), scriptFailure.getCause());
                    continue;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    execution.cancel(true);
                    compiledRule.recordError();
                    log.error(failOpenMessage(compiledRule), interrupted);
                    continue;
                }
                compiledRule.recordSuccess(System.nanoTime() - started);
                if (decision == null) {
                    decision = MockDecision.proceed();
                }
                if (finallyLocation) {
                    // observe-only; never mutates the outcome
                    continue;
                }
                if (enterSide && decision.type() == MockDecision.Type.PROCEED) {
                    Object[] nextArguments = decision.hasArguments() ? decision.arguments() : contextArguments(context, state);
                    if (state.isCallSite()) {
                        state.callArguments(validator.validateCallArguments(
                                state.callSiteSelector(), nextArguments, loaderOf(state)));
                        lastProceed = MockDecision.proceed();
                    } else {
                        state.arguments(validator.validateArguments(state.method(), nextArguments));
                        lastProceed = MockDecision.proceed(state.arguments());
                    }
                    continue;
                }
                if (decision.type() == MockDecision.Type.PROCEED) {
                    continue;
                }
                return decision;
            } catch (Throwable e) {
                compiledRule.recordError();
                log.error(failOpenMessage(compiledRule), e);
            }
        }
        return lastProceed;
    }

    private Object[] contextArguments(DefaultInvocationContext context, InvocationState state) {
        return state.isCallSite() ? context.callArguments() : context.arguments();
    }

    private DefaultInvocationContext buildContext(EnhancementLocation location, InvocationState state,
                                                  Object returnValue, Throwable throwable) {
        if (state.isCallSite()) {
            Object callResult = location.isReturnLocation() ? returnValue : null;
            Throwable callThrowable = location.isThrowLocation() ? throwable : null;
            return new DefaultInvocationContext(location, state.callArguments(), state.target(),
                    callResult, callThrowable, state.method(), objectFactory, log,
                    state.method(), state.callSiteSelector(), state.callArguments(), callResult, callThrowable);
        }
        Object result = location.isReturnLocation() ? returnValue : null;
        Throwable thr = location.isThrowLocation() ? throwable : null;
        if (location == EnhancementLocation.METHOD_FINALLY) {
            result = returnValue;
            thr = throwable;
        }
        return new DefaultInvocationContext(location, state.arguments(), state.target(),
                result, thr, state.method(), objectFactory, log);
    }

    private static String failOpenMessage(CompiledRule compiledRule) {
        String id = compiledRule.rule().id();
        CircuitBreakReason reason = compiledRule.circuitBreakReason();
        if (compiledRule.locked() && reason != null) {
            return "Rule " + id + " failed and circuit-open (" + reason + "); fail-open";
        }
        return "Rule " + id + " failed; fail-open";
    }

    @Override
    public void close() {
        scriptExecutor.shutdownNow();
    }
}
