package com.example.runtimemock.groovy;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class GroovyScriptCompiler implements ScriptCompiler, AutoCloseable {

    private final ConcurrentMap<ScriptCacheKey, CompiledMockScript> cache = new ConcurrentHashMap<>();
    private final ScriptLoaderGeneration generation;

    public GroovyScriptCompiler() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public GroovyScriptCompiler(ClassLoader parentClassLoader) {
        this.generation = new ScriptLoaderGeneration(parentClassLoader, GroovySecurityConfiguration.compilerConfiguration());
    }

    @Override
    public CompiledMockScript compile(String ruleId, long version, String script) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(script, "script");
        GroovyScriptSecurityPolicy.validateSource(script);
        String scriptHash = sha256(script);
        ScriptCacheKey key = new ScriptCacheKey(ruleId, version, scriptHash);
        return cache.computeIfAbsent(key, ignored -> compileNew(ruleId, version, scriptHash, script));
    }

    private CompiledMockScript compileNew(String ruleId, long version, String scriptHash, String script) {
        String className = "RuntimeMockRule_" + sanitize(ruleId) + "_" + version + "_" + scriptHash.substring(0, 16);
        Class<?> scriptType;
        try {
            synchronized (generation) {
                scriptType = generation.groovyClassLoader().parseClass(script, className + ".groovy");
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid or forbidden Groovy script: " + rootMessage(e), e);
        }
        if (!RuntimeMockScript.class.isAssignableFrom(scriptType)) {
            throw new IllegalStateException("Compiled script does not extend " + RuntimeMockScript.class.getName());
        }
        @SuppressWarnings("unchecked")
        Class<? extends RuntimeMockScript> typedScript = (Class<? extends RuntimeMockScript>) scriptType;
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
    public void close() {
        generation.close();
        cache.clear();
    }

    private record ScriptCacheKey(String ruleId, long version, String scriptHash) {
    }
}
