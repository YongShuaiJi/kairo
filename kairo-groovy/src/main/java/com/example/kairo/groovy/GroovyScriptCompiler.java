package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptPolicyRevision;
import groovy.lang.GroovySystem;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class GroovyScriptCompiler implements ScriptCompiler, AutoCloseable {

    private static final int MAX_CLASSES_PER_GENERATION = 256;
    private static final int MAX_CACHE_ENTRIES = 1024;

    private final ConcurrentMap<ScriptCacheKey, CompiledScriptReference> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<GenerationKey, GenerationHolderReference> generations = new ConcurrentHashMap<>();
    private final ReferenceQueue<CompiledMockScript> cacheReferenceQueue = new ReferenceQueue<>();
    private final ReferenceQueue<GenerationHolder> generationReferenceQueue = new ReferenceQueue<>();
    private final ClassLoader defaultParentClassLoader;

    public GroovyScriptCompiler() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public GroovyScriptCompiler(ClassLoader parentClassLoader) {
        this.defaultParentClassLoader = Objects.requireNonNull(parentClassLoader, "parentClassLoader");
    }

    @Override
    public CompiledMockScript compile(String ruleId, long version, String script) {
        return compile(ruleId, version, script, ScriptCompilationContext.safeDefaults(defaultParentClassLoader));
    }

    /**
     * Compile under an explicit {@link ScriptCompilationContext}. The profile selects the
     * {@link ScriptSecurityPolicy}, the context's target ClassLoader resolves business
     * classes, and the tier-shared size limits are enforced.
     *
     * <p>The cache weakly references the compiled script and its target ClassLoader: when no
     * active rule holds the script, both the compiled class and the target loader become
     * reclaimable. This keeps a long-lived compiler from pinning business ClassLoaders that the
     * application has already discarded.
     *
     * <p>The cache key bundles the script hash, capability tier, target ClassLoader id, policy
     * revision and Groovy version (alongside the rule id and version). The policy revision is
     * load-bearing: a new revision invalidates the cached script <em>and</em> the per-loader
     * generation, so a tightened EXTENDED allow-list or a reissued policy actually takes effect
     * rather than silently serving the previously compiled class. The Groovy version is constant
     * within a JVM and guards against cross-version reuse.
     */
    public CompiledMockScript compile(String ruleId, long version, String script, ScriptCompilationContext context) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(context, "context");
        ScriptSecurityPolicy policy = ScriptSecurityPolicy.forContext(context);
        policy.validateSource(script);
        context.enforceScriptSize(script);
        String scriptHash = sha256(script);
        ScriptCacheKey key = new ScriptCacheKey(ruleId, version, scriptHash,
                context.profile(), context.targetClassLoaderId(),
                context.policyRevision(), GroovySystem.getVersion());
        evictStaleCacheEntries();
        if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(key)) {
            cache.clear();
        }
        CompiledMockScript[] resultHolder = new CompiledMockScript[1];
        cache.compute(key, (cacheKey, existingRef) -> {
            CompiledMockScript existing = existingRef == null ? null : existingRef.get();
            if (existing != null) {
                resultHolder[0] = existing;
                return existingRef;
            }
            CompiledMockScript fresh = compileNew(ruleId, version, scriptHash, script, context, policy);
            resultHolder[0] = fresh;
            return new CompiledScriptReference(fresh, cacheKey, cacheReferenceQueue);
        });
        return resultHolder[0];
    }

    private CompiledMockScript compileNew(String ruleId, long version, String scriptHash, String script,
                                          ScriptCompilationContext context, ScriptSecurityPolicy policy) {
        String className = "KairoRule_" + sanitize(ruleId) + "_" + version
                + "_" + policy.profile() + "_" + scriptHash.substring(0, 12);
        GenerationHolder holder = generationFor(context, policy);
        ClassAndBytes compiled;
        try {
            compiled = holder.parseClass(script, className + ".groovy");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid or forbidden Groovy script: " + rootMessage(e), e);
        }
        Class<?> scriptType = compiled.type();
        if (!KairoScript.class.isAssignableFrom(scriptType)) {
            throw new IllegalStateException("Compiled script does not extend " + KairoScript.class.getName());
        }
        context.enforceArtifactSize(compiled.artifactBytes());
        @SuppressWarnings("unchecked")
        Class<? extends KairoScript> typedScript = (Class<? extends KairoScript>) scriptType;
        GroovyCompilationMetadata metadata = new GroovyCompilationMetadata(
                scriptHash,
                context.profile(),
                context.policyRevision(),
                GroovySystem.getVersion(),
                context.targetClassLoaderId(),
                compiled.artifactBytes());
        return new GroovyCompiledMockScript(ruleId, version, metadata, typedScript);
    }

    private GenerationHolder generationFor(ScriptCompilationContext context, ScriptSecurityPolicy policy) {
        GenerationKey key = new GenerationKey(context.profile(), context.targetClassLoaderId(),
                context.policyRevision());
        evictStaleGenerations();
        GenerationHolder[] holderBox = new GenerationHolder[1];
        generations.compute(key, (generationKey, existingRef) -> {
            GenerationHolder existing = existingRef == null ? null : existingRef.get();
            if (existing != null) {
                holderBox[0] = existing;
                return existingRef;
            }
            GenerationHolder created = new GenerationHolder(context.targetClassLoader(), policy);
            holderBox[0] = created;
            return new GenerationHolderReference(created, generationKey, generationReferenceQueue);
        });
        return holderBox[0];
    }

    private void evictStaleCacheEntries() {
        CompiledScriptReference ref;
        while ((ref = (CompiledScriptReference) cacheReferenceQueue.poll()) != null) {
            cache.remove(ref.key(), ref);
        }
    }

    private void evictStaleGenerations() {
        GenerationHolderReference ref;
        while ((ref = (GenerationHolderReference) generationReferenceQueue.poll()) != null) {
            GenerationHolderReference removed = generations.remove(ref.key(), ref) ? ref : null;
            if (removed != null) {
                GenerationHolder holder = removed.get();
                if (holder != null) {
                    holder.close();
                }
            }
        }
    }

    private static CompilerConfiguration buildConfiguration(ScriptSecurityPolicy policy) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setScriptBaseClass(KairoScript.class.getName());
        policy.applyTo(configuration);
        return configuration;
    }

    private static String sanitize(String ruleId) {
        String sanitized = ruleId.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isBlank()) {
            return "rule";
        }
        if (Character.isDigit(sanitized.charAt(0))) {
            return "rule_" + sanitized;
        }
        return sanitized;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public synchronized void close() {
        generations.values().forEach(ref -> {
            GenerationHolder holder = ref.get();
            if (holder != null) {
                holder.close();
            }
        });
        generations.clear();
        cache.clear();
    }

    /**
     * V1.5 &sect;3.2: drop every cached compiled script and generation whose
     * target ClassLoader id matches the collected loader. The cache weakly
     * references the compiled script and the generation's loader, so the loader
     * can be reclaimed even without this call; this proactively frees the
     * residual key entries so a long-lived compiler does not accumulate stale
     * keys for every loader it ever compiled against.
     *
     * @return the number of cache entries removed
     */
    public synchronized int clearForLoader(String classLoaderId) {
        if (classLoaderId == null) {
            return 0;
        }
        evictStaleCacheEntries();
        evictStaleGenerations();
        int removed = 0;
        var cacheIt = cache.entrySet().iterator();
        while (cacheIt.hasNext()) {
            if (classLoaderId.equals(cacheIt.next().getKey().targetClassLoaderId())) {
                cacheIt.remove();
                removed++;
            }
        }
        var genIt = generations.entrySet().iterator();
        while (genIt.hasNext()) {
            var entry = genIt.next();
            if (classLoaderId.equals(entry.getKey().targetClassLoaderId())) {
                GenerationHolder holder = entry.getValue().get();
                if (holder != null) {
                    holder.close();
                }
                genIt.remove();
            }
        }
        return removed;
    }

    private record ScriptCacheKey(String ruleId, long version, String scriptHash,
                                  CapabilityProfile profile, String targetClassLoaderId,
                                  ScriptPolicyRevision policyRevision, String groovyVersion) {
    }

    private record GenerationKey(CapabilityProfile profile, String targetClassLoaderId,
                                 ScriptPolicyRevision policyRevision) {
    }

    private record ClassAndBytes(Class<?> type, int artifactBytes) {
    }

    /**
     * Weak reference from a cache key to its compiled script. Carries the key so the
     * reference queue can evict the stale entry when the script is reclaimed.
     */
    private static final class CompiledScriptReference extends WeakReference<CompiledMockScript> {
        private final ScriptCacheKey key;

        CompiledScriptReference(CompiledMockScript referent, ScriptCacheKey key,
                                ReferenceQueue<CompiledMockScript> queue) {
            super(referent, queue);
            this.key = key;
        }

        ScriptCacheKey key() {
            return key;
        }
    }

    /**
     * Weak reference from a generation key to its holder. Letting the holder be reclaimed
     * releases the per-loader {@link KairoGroovyClassLoader} (and thus the target ClassLoader
     * it delegates to) once no compiled script keeps it alive.
     */
    private static final class GenerationHolderReference extends WeakReference<GenerationHolder> {
        private final GenerationKey key;

        GenerationHolderReference(GenerationHolder referent, GenerationKey key,
                                  ReferenceQueue<GenerationHolder> queue) {
            super(referent, queue);
            this.key = key;
        }

        GenerationKey key() {
            return key;
        }
    }

    /**
     * Holds the {@link ScriptLoaderGeneration} for one (profile, target ClassLoader) pair,
     * rotating it when the per-generation class cap is reached.
     */
    private static final class GenerationHolder {
        private final ClassLoader parent;
        private final CompilerConfiguration configuration;
        private volatile ScriptLoaderGeneration generation;
        private int classesInGeneration;

        GenerationHolder(ClassLoader parent, ScriptSecurityPolicy policy) {
            this.parent = parent;
            this.configuration = buildConfiguration(policy);
            this.generation = new ScriptLoaderGeneration(parent, configuration);
        }

        synchronized ClassAndBytes parseClass(String script, String fileName) {
            rotateIfNeeded();
            KairoGroovyClassLoader loader = generation.groovyClassLoader();
            Class<?> type;
            try {
                type = loader.parseClass(script, fileName);
            } catch (RuntimeException e) {
                loader.consumeArtifactBytes();
                throw e;
            }
            int bytes = loader.consumeArtifactBytes();
            classesInGeneration++;
            return new ClassAndBytes(type, bytes);
        }

        private void rotateIfNeeded() {
            if (classesInGeneration < MAX_CLASSES_PER_GENERATION) {
                return;
            }
            ScriptLoaderGeneration previous = generation;
            generation = new ScriptLoaderGeneration(parent, configuration);
            classesInGeneration = 0;
            previous.close();
        }

        synchronized void close() {
            generation.close();
        }
    }
}
