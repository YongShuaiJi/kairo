package com.example.kairo.perf.leak;

import com.example.kairo.agent.core.AgentRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Test-only diagnostics that observe the <em>real</em> {@code GroovyScriptCompiler} state
 * owned by a live (or closed) {@link AgentRuntime}, without adding or changing any public
 * production API. Reflection is used because the compiler, its weak-reference cache, its
 * per-artifact generations and the {@code KairoGroovyClassLoader} instances are all package-
 * or class-private by design.
 *
 * <p><b>Fail-closed contract (&sect;9.3):</b> every reflection step is mandatory. If the
 * production layout changes (a field is renamed/removed, an accessor disappears) the
 * measurement throws {@link GroovyDiagnosticUnavailableException} rather than returning a
 * plausible zero. The harness treats that as a lifecycle failure (exit &ne; 0); a gate is
 * never marked passed on the back of a reflection miss. This is the opposite of
 * fabricating evidence: the harness becomes unusable, loudly, until the diagnostics are
 * realigned with the real layout.
 *
 * <p>Measured:
 * <ul>
 *   <li>{@code cacheEntries} - the live compile-cache size (the weak-reference map of
 *       compiled scripts);</li>
 *   <li>{@code generationCount} - the number of reachable per-artifact generations
 *       (stale weak references are not counted). A just-released artifact remains observable
 *       until collection so the harness can register its real loader for residual checks;</li>
 *   <li>{@code maxClassesInGeneration} - the highest real loaded-class count across
 *       live generations;</li>
 *   <li>{@code liveGroovyLoaders} - the real {@code KairoGroovyClassLoader} instances held
 *       by live generations, de-duplicated by identity, for weak-reference tracking.</li>
 * </ul>
 */
public final class GroovyCompilerDiagnostics {

    /** Thrown when the real production layout cannot be reflected; never recoverable. */
    public static final class GroovyDiagnosticUnavailableException extends RuntimeException {
        public GroovyDiagnosticUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** An immutable snapshot of the real Groovy compiler state at one observation point. */
    public record GroovyDiagnostics(int cacheEntries, int generationCount, int maxClassesInGeneration,
                                   List<ClassLoader> liveGroovyLoaders) {
    }

    private GroovyCompilerDiagnostics() {
    }

    /**
     * Measure the real compiler state. Throws
     * {@link GroovyDiagnosticUnavailableException} (fail-closed) if any reflection step
     * misses; the harness must not translate that into a passed gate.
     */
    public static GroovyDiagnostics measure(AgentRuntime runtime) {
        if (runtime == null) {
            // A null runtime can never yield real Groovy state; treat as unavailable, not zero.
            throw new GroovyDiagnosticUnavailableException(
                    "Groovy diagnostics require a non-null AgentRuntime (null would fabricate zero)", null);
        }
        try {
            Object factory = runtime.scriptCompilerFactory();
            Method compilerMethod = factory.getClass().getDeclaredMethod("compiler");
            compilerMethod.setAccessible(true);
            Object compiler = compilerMethod.invoke(factory);

            ConcurrentMap<?, ?> cache = readMap(compiler, "cache");
            int cacheEntries = cache.size();

            List<Object> liveScripts = new ArrayList<>();
            for (Object ref : cache.values()) {
                if (!(ref instanceof WeakReference<?> weak)) {
                    continue;
                }
                Object script = weak.get();
                if (script != null) {
                    liveScripts.add(script);
                }
            }

            int maxClasses = 0;
            Map<ClassLoader, Boolean> dedup = new IdentityHashMap<>();
            List<ClassLoader> liveGroovyLoaders = new ArrayList<>();
            int generationCount = 0;
            Map<Object, Boolean> generationDedup = new IdentityHashMap<>();
            for (Object script : liveScripts) {
                Object generation = readObject(script, "generation");
                if (generation == null) {
                    continue;
                }
                if (generationDedup.putIfAbsent(generation, Boolean.TRUE) == null) {
                    generationCount++;
                }
                Method gcl = generation.getClass().getDeclaredMethod("groovyClassLoader");
                gcl.setAccessible(true);
                Object groovyLoaderObject = gcl.invoke(generation);
                ClassLoader groovyLoader = (ClassLoader) groovyLoaderObject;
                Method definedClassCount = generation.getClass().getDeclaredMethod("definedClassCount");
                definedClassCount.setAccessible(true);
                int classes = (int) definedClassCount.invoke(generation);
                maxClasses = Math.max(maxClasses, classes);
                if (groovyLoader != null && dedup.putIfAbsent(groovyLoader, Boolean.TRUE) == null) {
                    liveGroovyLoaders.add(groovyLoader);
                }
            }
            return new GroovyDiagnostics(cacheEntries, generationCount, maxClasses, liveGroovyLoaders);
        } catch (GroovyDiagnosticUnavailableException e) {
            throw e;
        } catch (Throwable t) {
            throw new GroovyDiagnosticUnavailableException(
                    "Groovy compiler layout could not be reflected (production layout changed?): "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<Object, Object> readMap(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (ConcurrentMap<Object, Object>) field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new GroovyDiagnosticUnavailableException(
                    "cannot read field '" + fieldName + "' on " + target.getClass().getName(), e);
        }
    }

    private static Object readObject(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new GroovyDiagnosticUnavailableException(
                    "cannot read field '" + fieldName + "' on " + target.getClass().getName(), e);
        }
    }

}
