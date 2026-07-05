package com.example.kairo.groovy;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockApi;
import com.example.kairo.api.ScriptLog;
import groovy.lang.Script;

public abstract class KairoScript extends Script {

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
