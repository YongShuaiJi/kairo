package com.example.kairo.groovy;

import org.codehaus.groovy.control.CompilerConfiguration;

import java.beans.Introspector;

public final class ScriptLoaderGeneration implements AutoCloseable {

    private final KairoGroovyClassLoader groovyClassLoader;
    private volatile int definedClassCount;

    public ScriptLoaderGeneration(ClassLoader parentClassLoader, CompilerConfiguration configuration) {
        this.groovyClassLoader = new KairoGroovyClassLoader(parentClassLoader, configuration);
    }

    KairoGroovyClassLoader groovyClassLoader() {
        return groovyClassLoader;
    }

    void captureDefinedClassCount() {
        definedClassCount = Math.max(definedClassCount, groovyClassLoader.getLoadedClasses().length);
    }

    int definedClassCount() {
        return definedClassCount;
    }

    @Override
    public void close() {
        /*
         * Executing a Groovy Script causes Groovy's property machinery to ask the JDK
         * JavaBeans introspector about the generated script class. ClassInfo.CACHE then
         * retains that class and, through its InnerLoader parent chain, the target
         * application ClassLoader. GroovyClassLoader.close() clears Groovy's own class
         * and MetaClass caches but not this JDK cache, so flush every class defined by
         * the retiring generation after Groovy has dropped its own class cache.
         */
        Class<?>[] loadedClasses = groovyClassLoader.getLoadedClasses();
        try {
            groovyClassLoader.close();
        } catch (Exception ignored) {
            // Best-effort cleanup; old generations are allowed to die with the JVM.
        } finally {
            // Groovy's close/clear hooks can perform more class metadata lookups, so
            // flush the JDK cache after those hooks have finished.
            for (Class<?> loadedClass : loadedClasses) {
                Introspector.flushFromCaches(loadedClass);
            }
        }
    }
}
