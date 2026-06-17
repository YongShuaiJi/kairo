package com.example.runtimemock.groovy;

import com.example.runtimemock.api.InvocationContext;
import com.example.runtimemock.api.MockDecision;

public interface CompiledMockScript {

    String ruleId();

    long version();

    String scriptHash();

    MockDecision execute(InvocationContext context);
}
