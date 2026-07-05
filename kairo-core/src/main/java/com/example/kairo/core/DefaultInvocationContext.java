package com.example.kairo.core;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockApi;
import com.example.kairo.api.ScriptLog;
import com.example.kairo.object.RuntimeObjectFactory;

import java.util.Objects;

public final class DefaultInvocationContext implements InvocationContext {

    private final InvokePhase phase;
    private final Object[] arguments;
    private final Object target;
    private final Object result;
    private final Throwable throwable;
    private final MethodMetadata method;
    private final MockApi mockApi;
    private final ScriptLog log;

    public DefaultInvocationContext(InvokePhase phase, Object[] arguments, Object target,
                                    Object result, Throwable throwable, MethodMetadata method,
                                    RuntimeObjectFactory objectFactory, ScriptLog log) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
        this.target = target;
        this.result = result;
        this.throwable = throwable;
        this.method = Objects.requireNonNull(method, "method");
        this.log = log == null ? ScriptLog.NOOP : log;
        this.mockApi = new DefaultMockApi(this, Objects.requireNonNull(objectFactory, "objectFactory"));
    }

    @Override
    public InvokePhase phase() {
        return phase;
    }

    @Override
    public Object[] arguments() {
        return arguments;
    }

    @Override
    public Object target() {
        return target;
    }

    @Override
    public Object result() {
        return result;
    }

    @Override
    public Throwable throwable() {
        return throwable;
    }

    @Override
    public MethodMetadata method() {
        return method;
    }

    @Override
    public MockApi mockApi() {
        return mockApi;
    }

    @Override
    public ScriptLog log() {
        return log;
    }
}
