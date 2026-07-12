package com.example.kairo.core;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockApi;
import com.example.kairo.api.ScriptLog;
import com.example.kairo.object.RuntimeObjectFactory;

import java.util.Objects;

public final class DefaultInvocationContext implements InvocationContext {

    private final EnhancementLocation location;
    private final Object[] arguments;
    private final Object target;
    private final Object result;
    private final Throwable throwable;
    private final MethodMetadata method;
    private final MockApi mockApi;
    private final ScriptLog log;
    private final MethodMetadata caller;
    private final CallSiteSelector callSite;
    private final Object[] callArguments;
    private final Object callResult;
    private final Throwable callThrowable;

    public DefaultInvocationContext(InvokePhase phase, Object[] arguments, Object target,
                                    Object result, Throwable throwable, MethodMetadata method,
                                    RuntimeObjectFactory objectFactory, ScriptLog log) {
        this(EnhancementLocation.fromPhase(phase), arguments, target, result, throwable,
                method, objectFactory, log, null, null, null, null, null);
    }

    public DefaultInvocationContext(EnhancementLocation location, Object[] arguments, Object target,
                                    Object result, Throwable throwable, MethodMetadata method,
                                    RuntimeObjectFactory objectFactory, ScriptLog log) {
        this(location, arguments, target, result, throwable, method, objectFactory, log,
                null, null, null, null, null);
    }

    public DefaultInvocationContext(EnhancementLocation location, Object[] arguments, Object target,
                                    Object result, Throwable throwable, MethodMetadata method,
                                    RuntimeObjectFactory objectFactory, ScriptLog log,
                                    MethodMetadata caller, CallSiteSelector callSite,
                                    Object[] callArguments, Object callResult, Throwable callThrowable) {
        this.location = Objects.requireNonNull(location, "location");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
        this.target = target;
        this.result = result;
        this.throwable = throwable;
        this.method = Objects.requireNonNull(method, "method");
        this.log = log == null ? ScriptLog.NOOP : log;
        this.mockApi = new DefaultMockApi(this, Objects.requireNonNull(objectFactory, "objectFactory"));
        this.caller = caller;
        this.callSite = callSite;
        this.callArguments = callArguments;
        this.callResult = callResult;
        this.callThrowable = callThrowable;
    }

    @Override
    public InvokePhase phase() {
        return location.toLegacyPhase();
    }

    @Override
    public EnhancementLocation location() {
        return location;
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

    @Override
    public MethodMetadata caller() {
        return caller;
    }

    @Override
    public CallSiteSelector callSite() {
        return callSite;
    }

    @Override
    public Object[] callArguments() {
        return callArguments;
    }

    @Override
    public Object callResult() {
        return callResult;
    }

    @Override
    public Throwable callThrowable() {
        return callThrowable;
    }
}
