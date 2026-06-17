package com.example.runtimemock.groovy;

import com.example.runtimemock.api.InvocationContext;
import com.example.runtimemock.api.MethodMetadata;
import com.example.runtimemock.api.MockApi;
import com.example.runtimemock.api.ScriptLog;
import groovy.lang.Script;

public abstract class RuntimeMockScript extends Script {

    private InvocationContext invocationContext;

    public final void initialize(InvocationContext invocationContext) {
        this.invocationContext = invocationContext;
    }

    protected final InvocationContext context() {
        if (invocationContext == null) {
            throw new IllegalStateException("InvocationContext is not initialized");
        }
        return invocationContext;
    }

    public final Object[] getArgs() {
        return context().arguments();
    }

    public final Object getTarget() {
        return context().target();
    }

    public final Object getResult() {
        return context().result();
    }

    public final Throwable getThrowable() {
        return context().throwable();
    }

    public final MethodMetadata getMethod() {
        return context().method();
    }

    public final MockApi getMock() {
        return context().mockApi();
    }

    public final InvocationContext getCtx() {
        return context();
    }

    public final ScriptLog getLog() {
        return context().log();
    }
}
