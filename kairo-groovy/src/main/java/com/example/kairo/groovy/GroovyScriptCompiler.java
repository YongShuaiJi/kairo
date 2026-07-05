package com.example.kairo.groovy;

import groovy.lang.GroovyClassLoader;
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
    private final ClassLoader parentClassLoader;
    private final CompilerConfiguration configuration;
    private volatile ScriptLoaderGeneration generation;
    private int classesInGeneration;

    public GroovyScriptCompiler() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public GroovyScriptCompiler(ClassLoader parentClassLoader) {
        this.parentClassLoader = Objects.requireNonNull(parentClassLoader, "parentClassLoader");
        this.configuration = GroovySecurityConfiguration.compilerConfiguration();
        this.generation = new ScriptLoaderGeneration(parentClassLoader, configuration);
    }

    @Override
    public CompiledMockScript compile(String ruleId, long version, String script) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(script, "script");
        GroovyScriptSecurityPolicy.validateSource(script);
        String scriptHash = sha256(script);
        ScriptCacheKey key = new ScriptCacheKey(ruleId, version, scriptHash);
        if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(key)) {
            cache.clear();
        }
        return cache.computeIfAbsent(key, ignored -> compileNew(ruleId, version, scriptHash, script));
    }

    private CompiledMockScript compileNew(String ruleId, long version, String scriptHash, String script) {
        String className = "KairoRule_" + sanitize(ruleId) + "_" + version + "_" + scriptHash.substring(0, 16);
        Class<?> scriptType;
        try {
            synchronized (this) {
                rotateGenerationIfNeeded();
                scriptType = generation.groovyClassLoader().parseClass(script, className + ".groovy");
                classesInGeneration++;
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid or forbidden Groovy script: " + rootMessage(e), e);
        }
        if (!KairoScript.class.isAssignableFrom(scriptType)) {
            throw new IllegalStateException("Compiled script does not extend " + KairoScript.class.getName());
        }
        @SuppressWarnings("unchecked")
        Class<? extends KairoScript> typedScript = (Class<? extends KairoScript>) scriptType;
        return new GroovyCompiledMockScript(ruleId, version, scriptHash, typedScript);
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
        generation.close();
        cache.clear();
    }

    private void rotateGenerationIfNeeded() {
        if (classesInGeneration < MAX_CLASSES_PER_GENERATION) {
            return;
        }
        ScriptLoaderGeneration previous = generation;
        generation = new ScriptLoaderGeneration(parentClassLoader, configuration);
        classesInGeneration = 0;
        previous.close();
    }

    private record ScriptCacheKey(String ruleId, long version, String scriptHash) {
    }
}
