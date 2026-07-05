package com.example.kairo.groovy;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MockDecision;

public final class GroovyScriptExecutor {

    public MockDecision execute(CompiledMockScript script, InvocationContext context) {
        return script.execute(context);
    }
}
