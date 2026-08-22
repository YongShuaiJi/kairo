package com.example.kairo.core;

import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.OutcomeState;
import com.example.kairo.api.PropagationMode;
import com.example.kairo.api.RuleChainDecision;
import com.example.kairo.api.ScriptLog;
import com.example.kairo.api.diagnostics.DiagnosticEvent;
import com.example.kairo.bridge.EnterResult;
import com.example.kairo.bridge.ExitResult;
import com.example.kairo.object.RuntimeObjectFactory;

import java.time.Clock;
import java.security.AccessController;
import java.security.PrivilegedAction;
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
 * Location-aware rule-chain dispatcher.
 *
 * <p>V1.4 replaces the V1.2 "first non-PROCEED decision wins" short-circuit with
 * a canonical chain state machine. Each invocation reads <em>one</em> frozen
 * {@link MethodChainSnapshot} at enter (stored on the {@link InvocationState}) so
 * a chain published mid-invocation cannot affect the in-flight execution. Rules
 * run in canonical order ({@code priority DESC, createdAt ASC, ruleId ASC}); a
 * rule's {@link RuleChainDecision} (mapped from the returned {@link MockDecision})
 * is applied as {@link PropagationMode#CONTINUE} (adopt modifications, optionally
 * replace the outcome and keep going), {@link PropagationMode#TERMINATE},
 * {@link PropagationMode#PROCEED_ORIGINAL} (enter side only),
 * {@link PropagationMode#FAIL_OPEN} or {@link PropagationMode#FAIL_CLOSED}.
 *
 * <p>The V1 {@code onEnter}/{@code onExit} entry points handle method locations;
 * the V2 {@code enter}/{@code exit} overloads handle constructor and call-site
 * locations. Script execution, decision validation and outcome merging are
 * separated; the hit chain revision is recorded on the state for audit.
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
                runnable -> newScriptThread(runnable, config.threadNamePrefix()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @SuppressWarnings("removal")
    private static Thread newScriptThread(Runnable runnable, String name) {
        /*
         * ThreadPoolExecutor creates cached workers lazily on the submitting
         * business thread. A normal Thread constructor inherits that thread's
         * AccessControlContext and context ClassLoader, which can retain an
         * otherwise unloadable application ClassLoader for the worker keep-alive
         * period. Truncate the inherited security context at Kairo's stable
         * protection domain and explicitly install Kairo's own context loader.
         */
        return AccessController.doPrivileged((PrivilegedAction<Thread>) () -> {
            Thread thread = new Thread(null, runnable, name, 0L, false);
            thread.setDaemon(true);
            thread.setContextClassLoader(RuleDispatcher.class.getClassLoader());
            return thread;
        });
    }

    // -------------------------------------------------------- V1 method path (BEFORE/RETURN/THROWS + FINALLY)

    public EnterResult onEnter(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments) {
        if (!enabled) {
            return EnterResult.proceedWithoutContext();
        }
        MethodChainSnapshot chains = ruleRegistry.methodChains(methodKey);
        if (chains.isEmpty()) {
            return EnterResult.proceedWithoutContext();
        }
        InvocationState state = new InvocationState(methodKey, method, target,
                arguments == null ? new Object[0] : arguments);
        state.chains(chains);
        if (!chains.hasChain(EnhancementLocation.METHOD_ENTER, null)) {
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

        if (state.hasBeforeTerminalDecision()) {
            // Enter side already short-circuited; honour that outcome and skip the exit chain.
            MockDecision terminalDecision = state.beforeTerminalDecision();
            terminal = true;
            if (terminalDecision.type() == MockDecision.Type.RETURN) {
                effectiveReturn = validateExitReturn(state, terminalDecision.returnValue(), throwable == null);
                effectiveThrow = null;
            } else if (terminalDecision.type() == MockDecision.Type.THROW) {
                effectiveThrow = validator.validateThrowable(state.method(), terminalDecision.throwable());
                effectiveReturn = null;
            }
        } else {
            MethodChainSnapshot chains = state.chains();
            if (chains.isEmpty()) {
                chains = ruleRegistry.methodChains(state.methodKey());
                state.chains(chains);
            }
            EnhancementLocation primary = primaryExitLocation(state, throwable);
            if (chains.hasChain(primary, state.callSiteSelector())) {
                // Seed the current outcome from the real execution.
                seedOutcome(state, returnValue, throwable);
                ChainResult result = runChain(state, primary,
                        chains.chain(primary, state.callSiteSelector()).rules());
                Object chainReturn = state.outcomeState() == OutcomeState.THROWING ? null : state.result();
                Throwable chainThrow = state.outcomeState() == OutcomeState.THROWING ? state.currentThrowable() : null;
                // The chain modified the outcome if either the return value or the throwable
                // differs from the real execution. This covers both TERMINATE and the
                // "replace and CONTINUE" case where no rule terminated but the outcome was replaced.
                boolean modified = !Objects.equals(chainReturn, returnValue)
                        || !Objects.equals(chainThrow, throwable);
                boolean constructorReturnObserveOnly = isConstructorReturn(state, primary);
                if ((result.terminated || modified) && !constructorReturnObserveOnly) {
                    terminal = true;
                    effectiveReturn = chainReturn;
                    effectiveThrow = chainThrow;
                }
                // else: chain ran observe-only or did not modify the outcome -> keep real outcome.
            }
        }

        runFinally(state, effectiveReturn, effectiveThrow);

        if (!terminal && effectiveThrow == null && effectiveReturn == returnValue && !state.hasBeforeTerminalDecision()) {
            // No rule replaced the outcome and no enter short-circuit: proceed with the real outcome.
            return ExitResult.proceed();
        }
        if (!terminal) {
            return ExitResult.proceed();
        }
        if (effectiveThrow != null) {
            return ExitResult.throwException(effectiveThrow);
        }
        return ExitResult.returnValue(effectiveReturn);
    }

    // -------------------------------------------------------- V2 constructor / call-site path

    /**
     * Generic enter for a prepared state at an enter-side location
     * (METHOD_ENTER, CONSTRUCTOR_AFTER_SUPER, CALL_BEFORE).
     */
    public EnterResult enter(InvocationState state, EnhancementLocation location) {
        if (!enabled || state == null) {
            return EnterResult.proceedWithoutContext();
        }
        if (state.chains().isEmpty()) {
            state.chains(ruleRegistry.methodChains(state.methodKey()));
        }
        MethodChainSnapshot chains = state.chains();
        if (chains.isEmpty() || !chains.hasChain(location, state.callSiteSelector())) {
            return EnterResult.proceed(state, currentArgs(state));
        }
        ChainResult result = runChain(state, location,
                chains.chain(location, state.callSiteSelector()).rules());
        state.hitChainRevision(chains.chain(location, state.callSiteSelector()).revision().value());
        // A validation failure fail-opens to the real body with the original arguments,
        // preserving the original business behaviour (distinct from script FAIL_OPEN).
        if (result.failOpenBody) {
            return EnterResult.proceed(state, state.originalArguments());
        }
        // CONSTRUCTOR_AFTER_SUPER is observe-only: the super call has already run
        // and the constructor body cannot be skipped or its result substituted.
        if (!mayShortCircuit(location)) {
            return EnterResult.proceed(state, currentArgs(state));
        }
        try {
            validateCurrentArgs(state);
            if (result.terminated && state.outcomeState() == OutcomeState.RETURNING && mayReturnAt(location)) {
                Object returnValue = validateEnterReturn(state, location, state.result());
                state.beforeTerminalDecision(MockDecision.returnValue(returnValue));
                return EnterResult.returnValue(state, currentArgs(state), returnValue);
            }
            if (result.terminated && state.outcomeState() == OutcomeState.THROWING) {
                Throwable throwable = validator.validateThrowable(state.method(), state.currentThrowable());
                state.beforeTerminalDecision(MockDecision.throwException(throwable));
                return EnterResult.throwException(state, currentArgs(state), throwable);
            }
            return EnterResult.proceed(state, currentArgs(state));
        } catch (Throwable e) {
            log.error(location + " validation failed; fail-open", e);
            return EnterResult.proceed(state, state.originalArguments());
        }
    }

    /** Generic exit for a prepared state at a return/throw location, with FINALLY. */
    public ExitResult exit(InvocationState state, Object returnValue, Throwable throwable, EnhancementLocation location) {
        return onExit(state, returnValue, throwable);
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    // -------------------------------------------------------- chain state machine

    private static final class ChainResult {
        final boolean terminated;
        final boolean proceedOriginal;
        final boolean failOpenBody;
        ChainResult(boolean terminated, boolean proceedOriginal, boolean failOpenBody) {
            this.terminated = terminated;
            this.proceedOriginal = proceedOriginal;
            this.failOpenBody = failOpenBody;
        }
        static final ChainResult CONTINUED = new ChainResult(false, false, false);
        static final ChainResult TERMINATED = new ChainResult(true, false, false);
        static final ChainResult PROCEED_ORIGINAL = new ChainResult(false, true, false);
        /**
         * A decision failed validation (e.g. a return value not assignable to the
         * return type). Distinct from a script-emitted {@link PropagationMode#FAIL_OPEN}
         * (which continues the chain): a validation failure preserves the original
         * business behaviour by proceeding to the real body with the original
         * arguments, matching the V1.2 fail-open contract.
         */
        static final ChainResult FAIL_OPEN_BODY = new ChainResult(false, false, true);
    }

    /**
     * Run one location's chain in canonical order, applying each rule's
     * {@link RuleChainDecision} to the state. Mutates {@code state}'s current
     * arguments, result, throwable and outcome state. Returns whether the chain
     * terminated (a rule stopped it) or requested real execution
     * ({@link PropagationMode#PROCEED_ORIGINAL}).
     */
    private ChainResult runChain(InvocationState state, EnhancementLocation location, List<CompiledRule> rules) {
        long now = clock.millis();
        boolean enterSide = location.isEnterLocation();
        boolean finallyLocation = location.isFinallyLocation();
        boolean observeOnly = finallyLocation || (enterSide && !mayShortCircuit(location));
        for (CompiledRule compiledRule : rules) {
            if (!samplingPolicy.shouldRun(compiledRule.rule().percentage())) {
                continue;
            }
            CompiledRule.ExecutionPermit permit = compiledRule.tryAcquireExecution(
                    now, config.circuitRecoveryDelayMillis());
            if (permit == CompiledRule.ExecutionPermit.DENIED) {
                continue;
            }
            CircuitBreakReason reasonBeforeExecution = compiledRule.circuitBreakReason();
            if (permit == CompiledRule.ExecutionPermit.HALF_OPEN) {
                log.info(DiagnosticEvent.format("rule.circuit.half_open",
                        "ruleId", compiledRule.rule().id(),
                        "target", state.methodKey(),
                        "location", location,
                        "previousReason", reasonBeforeExecution,
                        "recoveryDelayMs", config.circuitRecoveryDelayMillis()));
            }
            if (!compiledRule.tryClaimHit()) {
                compiledRule.releaseExecutionPermit(permit);
                continue;
            }
            try (ReentryGuard.Scope scope = reentryGuard.enter(state.methodKey(), location, compiledRule.rule().id())) {
                if (!scope.entered()) {
                    compiledRule.releaseExecutionPermit(permit);
                    continue;
                }
                DefaultInvocationContext context = buildContext(location, state);
                long started = System.nanoTime();
                long timeoutMillis = compiledRule.hasExecuted()
                        ? config.scriptTimeoutMillis()
                        : config.firstScriptTimeoutMillis();
                Future<MockDecision> execution;
                try {
                    execution = scriptExecutor.submit(() -> compiledRule.script().execute(context));
                } catch (RejectedExecutionException saturated) {
                    compiledRule.circuitBreak(CircuitBreakReason.SATURATION, permit);
                    log.error(circuitFailureEvent("rule.execution.rejected", compiledRule, state,
                            location, permit, timeoutMillis, reasonBeforeExecution), saturated);
                    continue;
                }
                MockDecision raw;
                try {
                    raw = execution.get(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException timedOut) {
                    execution.cancel(true);
                    compiledRule.recordTimeout(permit);
                    log.error(circuitFailureEvent("rule.execution.timeout", compiledRule, state,
                            location, permit, timeoutMillis, reasonBeforeExecution), timedOut);
                    continue;
                } catch (ExecutionException scriptFailure) {
                    compiledRule.recordError(permit);
                    log.error(circuitFailureEvent("rule.execution.failed", compiledRule, state,
                            location, permit, timeoutMillis, reasonBeforeExecution), scriptFailure.getCause());
                    continue;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    execution.cancel(true);
                    compiledRule.recordError(permit);
                    log.error(circuitFailureEvent("rule.execution.interrupted", compiledRule, state,
                            location, permit, timeoutMillis, reasonBeforeExecution), interrupted);
                    continue;
                }
                long durationNanos = System.nanoTime() - started;
                compiledRule.recordSuccess(durationNanos, permit);
                logSuccessfulCircuitTransition(compiledRule, state, location, permit,
                        reasonBeforeExecution, durationNanos);
                RuleChainDecision decision = RuleChainDecision.from(raw == null ? MockDecision.proceed() : raw);
                if (observeOnly) {
                    // observe/record only; never mutate the outcome
                    continue;
                }
                ChainResult applied = applyDecision(state, location, decision, enterSide);
                if (applied.terminated || applied.proceedOriginal || applied.failOpenBody) {
                    return applied;
                }
            } catch (Throwable e) {
                compiledRule.recordError(permit);
                log.error(circuitFailureEvent("rule.execution.failed", compiledRule, state,
                        location, permit, -1L, reasonBeforeExecution), e);
            }
        }
        return ChainResult.CONTINUED;
    }

    /**
     * Apply one decision to the state. Returns TERMINATED if the chain should
     * stop, PROCEED_ORIGINAL if the rule requested real execution, or CONTINUED
     * to keep going. Validation failures fail open (treated as FAIL_OPEN).
     */
    private ChainResult applyDecision(InvocationState state, EnhancementLocation location,
                                      RuleChainDecision decision, boolean enterSide) {
        switch (decision.propagationMode()) {
            case CONTINUE:
                if (enterSide) {
                    if (decision.hasArguments()) {
                        adoptArguments(state, location, decision.arguments());
                    }
                    if (decision.replacesOutcome()) {
                        // enter-side replacement is a short-circuit (terminate)
                        if (decision.returnValue() != null) {
                            return adoptEnterReturn(state, location, decision.returnValue());
                        }
                        if (decision.throwable() != null) {
                            return adoptEnterThrow(state, decision.throwable());
                        }
                    }
                } else if (decision.replacesOutcome()) {
                    // exit-side replacement: adopt into current outcome and keep going so
                    // later rules observe the replacement (replace-and-continue).
                    return adoptExitOutcome(state, decision);
                }
                return ChainResult.CONTINUED;
            case PROCEED_ORIGINAL:
                if (enterSide && mayShortCircuit(location)) {
                    if (decision.hasArguments()) {
                        adoptArguments(state, location, decision.arguments());
                    }
                    return ChainResult.PROCEED_ORIGINAL;
                }
                return ChainResult.CONTINUED;
            case TERMINATE:
                if (decision.outcomeState() == OutcomeState.RETURNING) {
                    return adoptEnterOrExitReturn(state, location, decision.returnValue(), enterSide);
                }
                if (decision.outcomeState() == OutcomeState.THROWING) {
                    return adoptEnterOrExitThrow(state, decision.throwable(), enterSide);
                }
                // terminate with the current outcome unchanged
                return ChainResult.TERMINATED;
            case FAIL_OPEN:
                return ChainResult.CONTINUED;
            case FAIL_CLOSED:
                // Not a default capability; downgrade to fail-open unless explicitly allowed.
                // The dispatcher does not hold app policy, so fail-open is the safe default.
                log.error("Rule emitted FAIL_CLOSED but no application policy allowed it; downgrading to fail-open", null);
                return ChainResult.CONTINUED;
            default:
                return ChainResult.CONTINUED;
        }
    }

    private ChainResult adoptEnterReturn(InvocationState state, EnhancementLocation location, Object returnValue) {
        try {
            Object validated = validateEnterReturn(state, location, returnValue);
            state.result(validated);
            state.currentThrowable(null);
            state.outcomeState(OutcomeState.RETURNING);
            return ChainResult.TERMINATED;
        } catch (Throwable e) {
            log.error("Enter return validation failed; fail-open to body", e);
            return ChainResult.FAIL_OPEN_BODY;
        }
    }

    private ChainResult adoptEnterThrow(InvocationState state, Throwable throwable) {
        try {
            Throwable validated = validator.validateThrowable(state.method(), throwable);
            state.currentThrowable(validated);
            state.result(null);
            state.outcomeState(OutcomeState.THROWING);
            return ChainResult.TERMINATED;
        } catch (Throwable e) {
            log.error("Enter throw validation failed; fail-open to body", e);
            return ChainResult.FAIL_OPEN_BODY;
        }
    }

    private ChainResult adoptEnterOrExitReturn(InvocationState state, EnhancementLocation location,
                                               Object returnValue, boolean enterSide) {
        if (enterSide) {
            return adoptEnterReturn(state, location, returnValue);
        }
        try {
            Object validated = validateExitReturn(state, returnValue, state.outcomeState() != OutcomeState.THROWING);
            state.result(validated);
            state.currentThrowable(null);
            state.outcomeState(OutcomeState.RETURNING);
            return ChainResult.TERMINATED;
        } catch (Throwable e) {
            log.error("Exit return validation failed; fail-open to body", e);
            return ChainResult.FAIL_OPEN_BODY;
        }
    }

    private ChainResult adoptEnterOrExitThrow(InvocationState state, Throwable throwable, boolean enterSide) {
        try {
            Throwable validated = validator.validateThrowable(state.method(), throwable);
            state.currentThrowable(validated);
            state.result(null);
            state.outcomeState(OutcomeState.THROWING);
            return ChainResult.TERMINATED;
        } catch (Throwable e) {
            log.error("Throw validation failed; fail-open to body", e);
            return ChainResult.FAIL_OPEN_BODY;
        }
    }

    private ChainResult adoptExitOutcome(InvocationState state, RuleChainDecision decision) {
        try {
            if (decision.returnValue() != null) {
                Object validated = validateExitReturn(state, decision.returnValue(),
                        state.outcomeState() != OutcomeState.THROWING);
                state.result(validated);
                state.currentThrowable(null);
                state.outcomeState(OutcomeState.RETURNING);
            } else if (decision.throwable() != null) {
                Throwable validated = validator.validateThrowable(state.method(), decision.throwable());
                state.currentThrowable(validated);
                state.result(null);
                state.outcomeState(OutcomeState.THROWING);
            }
            return ChainResult.CONTINUED;
        } catch (Throwable e) {
            log.error("Exit outcome replacement validation failed; fail-open to body", e);
            return ChainResult.FAIL_OPEN_BODY;
        }
    }

    private void adoptArguments(InvocationState state, EnhancementLocation location, Object[] nextArguments) {
        try {
            if (state.isCallSite()) {
                state.callArguments(validator.validateCallArguments(
                        state.callSiteSelector(), nextArguments, loaderOf(state)));
            } else {
                state.arguments(validator.validateArguments(state.method(), nextArguments));
            }
        } catch (Throwable e) {
            log.error(location + " argument validation failed; fail-open", e);
        }
    }

    private void seedOutcome(InvocationState state, Object returnValue, Throwable throwable) {
        state.originalResult(returnValue);
        state.result(returnValue);
        state.originalThrowable(throwable);
        state.currentThrowable(throwable);
        state.outcomeState(throwable == null ? OutcomeState.RETURNING : OutcomeState.THROWING);
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
        MethodChainSnapshot chains = state.chains();
        if (chains.isEmpty()) {
            return;
        }
        if (!chains.hasChain(EnhancementLocation.METHOD_FINALLY, null)) {
            return;
        }
        // FINALLY observes the final outcome; its decisions are ignored.
        seedOutcome(state, returnValue, throwable);
        runChain(state, EnhancementLocation.METHOD_FINALLY,
                chains.chain(EnhancementLocation.METHOD_FINALLY, null).rules());
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
        return !location.isConstructorLocation();
    }

    private boolean mayShortCircuit(EnhancementLocation location) {
        return location == EnhancementLocation.METHOD_ENTER || location == EnhancementLocation.CALL_BEFORE;
    }

    private boolean isConstructorReturn(InvocationState state, EnhancementLocation location) {
        return location == EnhancementLocation.CONSTRUCTOR_RETURN;
    }

    private ClassLoader loaderOf(InvocationState state) {
        MethodMetadata method = state.method();
        return method == null ? null : method.targetClassLoader();
    }

    private DefaultInvocationContext buildContext(EnhancementLocation location, InvocationState state) {
        OutcomeState outcome = state.outcomeState();
        if (state.isCallSite()) {
            Object callResult = location.isReturnLocation() || location.isFinallyLocation() ? state.result() : null;
            Throwable callThrowable = location.isThrowLocation() || location.isFinallyLocation()
                    ? state.currentThrowable() : null;
            Object[] currentCallArgs = state.callArguments() == null ? state.callArguments() : state.callArguments();
            return new DefaultInvocationContext(location, currentCallArgs, currentCallArgs, state.target(),
                    callResult, state.originalResult(), callThrowable, state.originalThrowable(),
                    outcome, state.method(), objectFactory, log,
                    state.method(), state.callSiteSelector(), currentCallArgs, callResult, callThrowable);
        }
        Object result = location.isReturnLocation() || location.isFinallyLocation() ? state.result() : null;
        Throwable thr = location.isThrowLocation() || location.isFinallyLocation() ? state.currentThrowable() : null;
        if (location == EnhancementLocation.METHOD_FINALLY) {
            result = state.result();
            thr = state.currentThrowable();
        }
        if (location.isEnterLocation()) {
            // enter side has no outcome yet
            result = null;
            thr = null;
        }
        return new DefaultInvocationContext(location, state.arguments(), state.originalArguments(),
                state.target(), result, state.originalResult(), thr, state.originalThrowable(),
                outcome, state.method(), objectFactory, log,
                null, null, null, null, null);
    }

    private String circuitFailureEvent(String event, CompiledRule compiledRule,
                                       InvocationState state, EnhancementLocation location,
                                       CompiledRule.ExecutionPermit permit, long timeoutMillis,
                                       CircuitBreakReason previousReason) {
        return DiagnosticEvent.format(event,
                "ruleId", compiledRule.rule().id(),
                "target", state.methodKey(),
                "location", location,
                "permit", permit,
                "previousCircuitReason", previousReason,
                "circuitState", compiledRule.locked() ? "OPEN" : "CLOSED",
                "circuitReason", compiledRule.circuitBreakReason(),
                "timeoutMs", timeoutMillis,
                "unfinishedTasks", compiledRule.unfinishedTaskCount(),
                "errorCount", compiledRule.errors(),
                "executionCount", compiledRule.executions(),
                "outcome", "FAIL_OPEN");
    }

    private void logSuccessfulCircuitTransition(CompiledRule compiledRule, InvocationState state,
                                                EnhancementLocation location,
                                                CompiledRule.ExecutionPermit permit,
                                                CircuitBreakReason previousReason,
                                                long durationNanos) {
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(durationNanos);
        if (permit == CompiledRule.ExecutionPermit.HALF_OPEN) {
            if (compiledRule.locked()) {
                log.error(DiagnosticEvent.format("rule.circuit.reopened",
                        "ruleId", compiledRule.rule().id(), "target", state.methodKey(),
                        "location", location, "previousReason", previousReason,
                        "reason", compiledRule.circuitBreakReason(), "durationMs", durationMillis), null);
            } else {
                log.info(DiagnosticEvent.format("rule.circuit.closed",
                        "ruleId", compiledRule.rule().id(), "target", state.methodKey(),
                        "location", location, "previousReason", previousReason,
                        "durationMs", durationMillis));
            }
        } else if (compiledRule.locked()) {
            // A successful script can still open the circuit when it breaches the slow watermark.
            log.warn(DiagnosticEvent.format("rule.circuit.opened",
                    "ruleId", compiledRule.rule().id(), "target", state.methodKey(),
                    "location", location, "reason", compiledRule.circuitBreakReason(),
                    "durationMs", durationMillis));
        }
    }

    @Override
    public void close() {
        scriptExecutor.shutdownNow();
    }
}
