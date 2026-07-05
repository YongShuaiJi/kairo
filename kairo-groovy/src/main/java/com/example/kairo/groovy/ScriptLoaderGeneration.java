package com.example.kairo.groovy;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;

public final class ScriptLoaderGeneration implements AutoCloseable {

    private final GroovyClassLoader groovyClassLoader;

    public ScriptLoaderGeneration(ClassLoader parentClassLoader, CompilerConfiguration configuration) {
        this.groovyClassLoader = new GroovyClassLoader(parentClassLoader, configuration);
    }

    GroovyClassLoader groovyClassLoader() {
        return groovyClassLoader;
    }

    @Override
    public void close() {
        try {
            groovyClassLoader.close();
        } catch (Exception ignored) {
            // Best-effort cleanup; old generations are allowed to die with the JVM.
        }
    }
}
