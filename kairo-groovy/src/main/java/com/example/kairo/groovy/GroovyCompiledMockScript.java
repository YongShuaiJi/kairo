package com.example.kairo.groovy;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MockDecision;

import java.beans.Introspector;
import java.util.concurrent.atomic.AtomicBoolean;

final class GroovyCompiledMockScript implements CompiledMockScript {

    private final String ruleId;
    private final long version;
    private final GroovyCompilationMetadata metadata;
    private final Class<? extends KairoScript> scriptType;
    private final ScriptLoaderGeneration generation;
    private final AtomicBoolean released = new AtomicBoolean();

    GroovyCompiledMockScript(String ruleId, long version,
                             GroovyCompilationMetadata metadata,
                             Class<? extends KairoScript> scriptType,
                             ScriptLoaderGeneration generation) {
        this.ruleId = ruleId;
        this.version = version;
        this.metadata = metadata;
        this.scriptType = scriptType;
        this.generation = generation;
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
        } finally {
            /*
             * A rule can be removed after the dispatcher captured its immutable snapshot
             * but before this invocation finishes. releaseClassLoaderCaches() flushes
             * immediately; this second flush closes the race if Groovy repopulated the
             * JavaBeans cache later in the in-flight execution.
             */
            if (released.get()) {
                Introspector.flushFromCaches(scriptType);
            }
        }
    }

    @Override
    public void releaseClassLoaderCaches() {
        if (released.compareAndSet(false, true)) {
            generation.close();
        }
    }

    Class<? extends KairoScript> scriptType() {
        return scriptType;
    }

    boolean released() {
        return released.get();
    }
}
