package com.example.runtimemock.core;

import com.example.runtimemock.api.InvokePhase;
import com.example.runtimemock.api.MethodMetadata;
import com.example.runtimemock.api.MockDecision;
import com.example.runtimemock.api.ScriptLog;
import com.example.runtimemock.bridge.EnterResult;
import com.example.runtimemock.bridge.ExitResult;
import com.example.runtimemock.object.RuntimeObjectFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class RuleDispatcher {

    private final RuleRegistry ruleRegistry;
    private final RuntimeObjectFactory objectFactory;
    private final DecisionValidator validator;
    private final ReentryGuard reentryGuard;
    private final SamplingPolicy samplingPolicy;
    private final ScriptLog log;
    private final Clock clock;
    private volatile boolean enabled = true;

    public RuleDispatcher(RuleRegistry ruleRegistry, RuntimeObjectFactory objectFactory) {
        this(ruleRegistry, objectFactory, new DecisionValidator(), new ReentryGuard(),
                new SamplingPolicy(), new LimitedScriptLog(), Clock.systemUTC());
    }

    public RuleDispatcher(RuleRegistry ruleRegistry, RuntimeObjectFactory objectFactory, DecisionValidator validator,
                          ReentryGuard reentryGuard, SamplingPolicy samplingPolicy, ScriptLog log, Clock clock) {
        this.ruleRegistry = Objects.requireNonNull(ruleRegistry, "ruleRegistry");
        this.objectFactory = Objects.requireNonNull(objectFactory, "objectFactory");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reentryGuard = Objects.requireNonNull(reentryGuard, "reentryGuard");
        this.samplingPolicy = Objects.requireNonNull(samplingPolicy, "samplingPolicy");
        this.log = log == null ? ScriptLog.NOOP : log;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EnterResult onEnter(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments) {
        if (!enabled) {
            return EnterResult.proceedWithoutContext();
        }
        RuleSet ruleSet = ruleRegistry.rules(methodKey);
        if (ruleSet.isEmpty()) {
            return EnterResult.proceedWithoutContext();
        }

        InvocationState state = new InvocationState(methodKey, method, target, arguments == null ? new Object[0] : arguments);
        if (!ruleSet.hasPhase(InvokePhase.BEFORE)) {
            return EnterResult.proceed(state, null);
        }

        MockDecision decision = runPhase(ruleSet.rules(InvokePhase.BEFORE), InvokePhase.BEFORE, state, null, null);
        try {
            state.arguments(validator.validateArguments(method, state.arguments()));
            if (decision.type() == MockDecision.Type.RETURN) {
                Object returnValue = validator.validateReturnValue(method, decision.returnValue());
                MockDecision terminal = MockDecision.returnValue(returnValue);
                state.beforeTerminalDecision(terminal);
                return EnterResult.returnValue(state, state.arguments(), returnValue);
            }
            if (decision.type() == MockDecision.Type.THROW) {
                Throwable throwable = validator.validateThrowable(method, decision.throwable());
                MockDecision terminal = MockDecision.throwException(throwable);
                state.beforeTerminalDecision(terminal);
                return EnterResult.throwException(state, state.arguments(), throwable);
            }
            return EnterResult.proceed(state, state.arguments());
        } catch (Throwable e) {
            log.error("BEFORE validation failed; fail-open", e);
            return EnterResult.proceed(state, arguments);
        }
    }

    public ExitResult onExit(InvocationState state, Object returnValue, Throwable throwable) {
        if (!enabled || state == null) {
            return ExitResult.proceed();
        }
        if (state.hasBeforeTerminalDecision()) {
            return toExitResult(state.beforeTerminalDecision());
        }

        RuleSet ruleSet = ruleRegistry.rules(state.methodKey());
        if (ruleSet.isEmpty()) {
            return ExitResult.proceed();
        }

        InvokePhase phase = throwable == null ? InvokePhase.RETURN : InvokePhase.THROWS;
        MockDecision decision = runPhase(ruleSet.rules(phase), phase, state, returnValue, throwable);
        try {
            if (decision.type() == MockDecision.Type.RETURN) {
                return ExitResult.returnValue(validator.validateReturnValue(state.method(), decision.returnValue()));
            }
            if (decision.type() == MockDecision.Type.THROW) {
                return ExitResult.throwException(validator.validateThrowable(state.method(), decision.throwable()));
            }
            return ExitResult.proceed();
        } catch (Throwable e) {
            log.error(phase + " validation failed; fail-open", e);
            return ExitResult.proceed();
        }
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    private MockDecision runPhase(List<CompiledRule> rules, InvokePhase phase, InvocationState state,
                                  Object returnValue, Throwable throwable) {
        MockDecision lastProceed = MockDecision.proceed();
        long now = clock.millis();
        for (CompiledRule compiledRule : rules) {
            if (!compiledRule.isActive(now)
                    || !samplingPolicy.shouldRun(compiledRule.rule().percentage())
                    || !compiledRule.tryClaimHit()) {
                continue;
            }
            try (ReentryGuard.Scope scope = reentryGuard.enter(state.methodKey(), compiledRule.rule().id())) {
                if (!scope.entered()) {
                    continue;
                }
                DefaultInvocationContext context = new DefaultInvocationContext(
                        phase,
                        state.arguments(),
                        state.target(),
                        returnValue,
                        throwable,
                        state.method(),
                        objectFactory,
                        log
                );
                MockDecision decision = compiledRule.script().execute(context);
                if (decision == null) {
                    decision = MockDecision.proceed();
                }
                if (phase == InvokePhase.BEFORE && decision.type() == MockDecision.Type.PROCEED) {
                    Object[] nextArguments = decision.hasArguments() ? decision.arguments() : context.arguments();
                    state.arguments(validator.validateArguments(state.method(), nextArguments));
                    lastProceed = MockDecision.proceed(state.arguments());
                    continue;
                }
                if (decision.type() == MockDecision.Type.PROCEED) {
                    continue;
                }
                return decision;
            } catch (Throwable e) {
                compiledRule.recordError();
                log.error("Rule " + compiledRule.rule().id() + " failed; fail-open", e);
            }
        }
        return lastProceed;
    }

    private ExitResult toExitResult(MockDecision decision) {
        if (decision.type() == MockDecision.Type.RETURN) {
            return ExitResult.returnValue(decision.returnValue());
        }
        if (decision.type() == MockDecision.Type.THROW) {
            return ExitResult.throwException(decision.throwable());
        }
        return ExitResult.proceed();
    }
}
