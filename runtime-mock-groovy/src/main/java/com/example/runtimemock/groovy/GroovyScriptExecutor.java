package com.example.runtimemock.groovy;

import com.example.runtimemock.api.InvocationContext;
import com.example.runtimemock.api.MockDecision;

public final class GroovyScriptExecutor {

    public MockDecision execute(CompiledMockScript script, InvocationContext context) {
        return script.execute(context);
    }
}
