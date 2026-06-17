package com.example.runtimemock.groovy;

import com.example.runtimemock.api.InvocationContext;
import com.example.runtimemock.api.MockDecision;

final class GroovyCompiledMockScript implements CompiledMockScript {

    private final String ruleId;
    private final long version;
    private final String scriptHash;
    private final Class<? extends RuntimeMockScript> scriptType;

    GroovyCompiledMockScript(String ruleId, long version, String scriptHash,
                             Class<? extends RuntimeMockScript> scriptType) {
        this.ruleId = ruleId;
        this.version = version;
        this.scriptHash = scriptHash;
        this.scriptType = scriptType;
    }

    @Override
    public String ruleId() {
        return ruleId;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public String scriptHash() {
        return scriptHash;
    }

    @Override
    public MockDecision execute(InvocationContext context) {
        try {
            RuntimeMockScript script = scriptType.getDeclaredConstructor().newInstance();
            script.initialize(context);
            Object result = script.run();
            if (result == null) {
                return MockDecision.proceed();
            }
            if (result instanceof MockDecision decision) {
                return decision;
            }
            throw new IllegalStateException("Groovy script must return MockDecision but returned "
                    + result.getClass().getName());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot execute Groovy script " + ruleId + ":" + version, e);
        }
    }
}
