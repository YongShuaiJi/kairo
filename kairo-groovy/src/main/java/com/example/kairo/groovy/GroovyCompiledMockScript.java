package com.example.kairo.groovy;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MockDecision;

final class GroovyCompiledMockScript implements CompiledMockScript {

    private final String ruleId;
    private final long version;
    private final GroovyCompilationMetadata metadata;
    private final Class<? extends KairoScript> scriptType;

    GroovyCompiledMockScript(String ruleId, long version,
                             GroovyCompilationMetadata metadata,
                             Class<? extends KairoScript> scriptType) {
        this.ruleId = ruleId;
        this.version = version;
        this.metadata = metadata;
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
        return metadata.scriptHash();
    }

    /** Full compilation metadata for this script. */
    public GroovyCompilationMetadata compilationMetadata() {
        return metadata;
    }

    @Override
    public MockDecision execute(InvocationContext context) {
        try {
            KairoScript script = scriptType.getDeclaredConstructor().newInstance();
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
