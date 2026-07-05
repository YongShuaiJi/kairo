package com.example.kairo.groovy;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MockDecision;

public interface CompiledMockScript {

    String ruleId();

    long version();

    String scriptHash();

    MockDecision execute(InvocationContext context);
}
