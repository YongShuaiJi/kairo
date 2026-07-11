package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import groovy.lang.GroovySystem;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class GroovyScriptCompiler implements ScriptCompiler, AutoCloseable {

    private static final int MAX_CLASSES_PER_GENERATION = 256;
    private static final int MAX_CACHE_ENTRIES = 1024;

    private final ConcurrentMap<ScriptCacheKey, CompiledMockScript> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<GenerationKey, GenerationHolder> generations = new ConcurrentHashMap<>();
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
                context.profile(), context.targetClassLoaderId());
        if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(key)) {
            cache.clear();
        }
        return cache.computeIfAbsent(key, ignored -> compileNew(ruleId, version, scriptHash, script, context, policy));
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
        GenerationKey key = new GenerationKey(context.profile(), context.targetClassLoaderId());
        return generations.computeIfAbsent(key, k -> new GenerationHolder(context.targetClassLoader(), policy));
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
        generations.values().forEach(GenerationHolder::close);
        generations.clear();
        cache.clear();
    }

    private record ScriptCacheKey(String ruleId, long version, String scriptHash,
                                  CapabilityProfile profile, String targetClassLoaderId) {
    }

    private record GenerationKey(CapabilityProfile profile, String targetClassLoaderId) {
    }

    private record ClassAndBytes(Class<?> type, int artifactBytes) {
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
