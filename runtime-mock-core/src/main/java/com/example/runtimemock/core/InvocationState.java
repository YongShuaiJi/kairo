package com.example.runtimemock.core;

import com.example.runtimemock.api.MethodMetadata;
import com.example.runtimemock.api.MockDecision;

public final class InvocationState {

    private final MethodKey methodKey;
    private final MethodMetadata method;
    private final Object target;
    private Object[] arguments;
    private MockDecision beforeTerminalDecision;

    public InvocationState(MethodKey methodKey, MethodMetadata method, Object target, Object[] arguments) {
        this.methodKey = methodKey;
        this.method = method;
        this.target = target;
        this.arguments = arguments;
    }

    public MethodKey methodKey() {
        return methodKey;
    }

    public MethodMetadata method() {
        return method;
    }

    public Object target() {
        return target;
    }

    public Object[] arguments() {
        return arguments;
    }

    public void arguments(Object[] arguments) {
        this.arguments = arguments;
    }

    public MockDecision beforeTerminalDecision() {
        return beforeTerminalDecision;
    }

    public void beforeTerminalDecision(MockDecision beforeTerminalDecision) {
        this.beforeTerminalDecision = beforeTerminalDecision;
    }

    public boolean hasBeforeTerminalDecision() {
        return beforeTerminalDecision != null;
    }
}
