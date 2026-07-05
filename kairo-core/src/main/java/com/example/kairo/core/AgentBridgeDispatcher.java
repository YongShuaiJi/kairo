package com.example.kairo.core;

import com.example.kairo.api.MethodMetadata;
import com.example.kairo.bridge.BridgeDispatcher;
import com.example.kairo.bridge.BridgeAction;
import com.example.kairo.bridge.EnterResult;
import com.example.kairo.bridge.ExitResult;

import java.lang.reflect.Method;
import java.util.Objects;

public final class AgentBridgeDispatcher implements BridgeDispatcher {

    private final RuleDispatcher ruleDispatcher;
    private final InvocationObserver invocationObserver;

    public AgentBridgeDispatcher(RuleDispatcher ruleDispatcher) {
        this(ruleDispatcher, InvocationObserver.NOOP);
    }

    public AgentBridgeDispatcher(RuleDispatcher ruleDispatcher, InvocationObserver invocationObserver) {
        this.ruleDispatcher = Objects.requireNonNull(ruleDispatcher, "ruleDispatcher");
        this.invocationObserver = invocationObserver == null ? InvocationObserver.NOOP : invocationObserver;
    }

    @Override
    public EnterResult onEnter(Class<?> declaringClass, Method method, Object target, Object[] arguments) {
        MethodKey methodKey = MethodKey.of(method);
        MethodMetadata metadata = new MethodMetadata(method, MethodDescriptor.of(method));
        EnterResult ruleResult = ruleDispatcher.onEnter(methodKey, metadata, target, arguments);
        Object[] effectiveArguments = ruleResult.getArguments() == null ? arguments : ruleResult.getArguments();
        Object observerToken = invocationObserver.onEnter(methodKey, metadata, target, effectiveArguments);
        if (observerToken == null) {
            return ruleResult;
        }
        CompositeInvocationToken token = new CompositeInvocationToken(
                ruleResult.getInvocationToken(), observerToken);
        return switch (ruleResult.getAction()) {
            case PROCEED -> EnterResult.proceed(token, ruleResult.getArguments());
            case RETURN -> EnterResult.returnValue(token, ruleResult.getArguments(), ruleResult.getReturnValue());
            case THROW -> EnterResult.throwException(token, ruleResult.getArguments(), ruleResult.getThrowable());
        };
    }

    @Override
    public ExitResult onExit(Object invocationToken, Object returnValue, Throwable throwable) {
        if (invocationToken instanceof CompositeInvocationToken composite) {
            ExitResult ruleResult = composite.ruleToken() instanceof InvocationState state
                    ? ruleDispatcher.onExit(state, returnValue, throwable)
                    : ExitResult.proceed();
            Object effectiveReturnValue = returnValue;
            Throwable effectiveThrowable = throwable;
            if (ruleResult.getAction() == BridgeAction.RETURN) {
                effectiveReturnValue = ruleResult.getReturnValue();
                effectiveThrowable = null;
            } else if (ruleResult.getAction() == BridgeAction.THROW) {
                effectiveThrowable = ruleResult.getThrowable();
            }
            invocationObserver.onExit(composite.observerToken(), effectiveReturnValue, effectiveThrowable);
            return ruleResult;
        }
        if (!(invocationToken instanceof InvocationState state)) {
            return ExitResult.proceed();
        }
        return ruleDispatcher.onExit(state, returnValue, throwable);
    }

    private record CompositeInvocationToken(Object ruleToken, Object observerToken) {
    }
}
