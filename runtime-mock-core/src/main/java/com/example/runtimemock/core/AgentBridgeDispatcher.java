package com.example.runtimemock.core;

import com.example.runtimemock.api.MethodMetadata;
import com.example.runtimemock.bridge.BridgeDispatcher;
import com.example.runtimemock.bridge.EnterResult;
import com.example.runtimemock.bridge.ExitResult;

import java.lang.reflect.Method;
import java.util.Objects;

public final class AgentBridgeDispatcher implements BridgeDispatcher {

    private final RuleDispatcher ruleDispatcher;

    public AgentBridgeDispatcher(RuleDispatcher ruleDispatcher) {
        this.ruleDispatcher = Objects.requireNonNull(ruleDispatcher, "ruleDispatcher");
    }

    @Override
    public EnterResult onEnter(Class<?> declaringClass, Method method, Object target, Object[] arguments) {
        MethodKey methodKey = MethodKey.of(method);
        MethodMetadata metadata = new MethodMetadata(method, MethodDescriptor.of(method));
        return ruleDispatcher.onEnter(methodKey, metadata, target, arguments);
    }

    @Override
    public ExitResult onExit(Object invocationToken, Object returnValue, Throwable throwable) {
        if (!(invocationToken instanceof InvocationState state)) {
            return ExitResult.proceed();
        }
        return ruleDispatcher.onExit(state, returnValue, throwable);
    }
}
