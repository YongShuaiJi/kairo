package com.example.kairo.agent.core.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.groovy.CompiledMockScript;
import com.example.kairo.groovy.GroovyScriptCompiler;
import com.example.kairo.groovy.ScriptCompilationContext;
import com.example.kairo.groovy.ScriptCompilerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * ClassLoader-aware {@link ScriptCompilerFactory} owned by the agent runtime.
 *
 * <p>Each compilation is bound to the ClassLoader that defined the target method, so scripts can
 * reference business types that are only visible to that loader. The stable loader id is taken
 * from {@link ClassLoaderIdentity#idOf(ClassLoader)} to stay consistent with the rest of the
 * agent (method keys, instrumentation registry, bytecode identity). The underlying
 * {@link GroovyScriptCompiler} keeps a weak-reference cache, so discarding a business
 * ClassLoader does not pin it.
 *
 * <p>For target methods defined by the bootstrap loader (e.g. JDK classes), the agent's own
 * ClassLoader is used as the compilation parent so Kairo script types still resolve, while the
 * canonical {@code "bootstrap"} id is recorded for cache keying.
 */
public final class AgentScriptCompilerFactory implements ScriptCompilerFactory, AutoCloseable {

    private final GroovyScriptCompiler compiler;
    private final ClassLoader agentClassLoader;

    public AgentScriptCompilerFactory() {
        this(AgentScriptCompilerFactory.class.getClassLoader());
    }

    public AgentScriptCompilerFactory(ClassLoader agentClassLoader) {
        this.agentClassLoader = Objects.requireNonNull(agentClassLoader, "agentClassLoader");
        this.compiler = new GroovyScriptCompiler(agentClassLoader);
    }

    @Override
    public CompiledMockScript compile(Method targetMethod, MockRule rule) {
        Objects.requireNonNull(targetMethod, "targetMethod");
        Objects.requireNonNull(rule, "rule");
        ClassLoader targetLoader = targetMethod.getDeclaringClass().getClassLoader();
        ClassLoader compileParent = targetLoader != null ? targetLoader : agentClassLoader;
        ScriptPolicyRevision revision = rule.policyRevision() != null
                ? rule.policyRevision()
                : ScriptCompilationContext.DEFAULT_SAFE_REVISION;
        ScriptCompilationContext context = ScriptCompilationContext.builder()
                .profile(rule.capabilityProfile())
                .policyRevision(revision)
                .targetClassLoader(compileParent)
                .targetClassLoaderId(ClassLoaderIdentity.idOf(targetLoader))
                .build();
        return compiler.compile(rule.id(), rule.version(), rule.script(), context);
    }

    /**
     * Compile a script for a V1.3 constructor-enhancement rule. The constructor's
     * declaring class determines the target ClassLoader exactly as the method path
     * does; only the entry point to the declaring class differs.
     */
    @Override
    public CompiledMockScript compile(Constructor<?> targetConstructor, MockRule rule) {
        Objects.requireNonNull(targetConstructor, "targetConstructor");
        Objects.requireNonNull(rule, "rule");
        ClassLoader targetLoader = targetConstructor.getDeclaringClass().getClassLoader();
        ClassLoader compileParent = targetLoader != null ? targetLoader : agentClassLoader;
        ScriptPolicyRevision revision = rule.policyRevision() != null
                ? rule.policyRevision()
                : ScriptCompilationContext.DEFAULT_SAFE_REVISION;
        ScriptCompilationContext context = ScriptCompilationContext.builder()
                .profile(rule.capabilityProfile())
                .policyRevision(revision)
                .targetClassLoader(compileParent)
                .targetClassLoaderId(ClassLoaderIdentity.idOf(targetLoader))
                .build();
        return compiler.compile(rule.id(), rule.version(), rule.script(), context);
    }

    /**
     * Legacy SAFE-defaults compile path, bound to the agent ClassLoader. Used by the HTTP
     * console and the compile API where there is no target method.
     */
    public CompiledMockScript compileScript(String ruleId, long version, String script) {
        return compiler.compile(ruleId, version, script);
    }

    /**
     * Compile a script under an explicit capability tier and policy revision against a chosen
     * target ClassLoader, used by the {@code SCRIPT_COMPILE} command. Unlike
     * {@link #compile(Method, MockRule)} there is no target method, so the caller supplies the
     * loader (and its canonical id) directly; {@code null} targets the bootstrap loader and the
     * agent ClassLoader is substituted as the Groovy parent so Kairo script types still resolve
     * (the recorded id stays {@code "bootstrap"}).
     */
    public CompiledMockScript compile(String script, CapabilityProfile profile,
                                      ScriptPolicyRevision revision,
                                      ClassLoader targetLoader, String targetClassLoaderId) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(revision, "revision");
        ClassLoader compileParent = targetLoader != null ? targetLoader : agentClassLoader;
        ScriptCompilationContext context = ScriptCompilationContext.builder()
                .profile(profile)
                .policyRevision(revision)
                .targetClassLoader(compileParent)
                .targetClassLoaderId(targetClassLoaderId)
                .build();
        return compiler.compile("compile-" + Integer.toHexString(script.hashCode()), 1L, script, context);
    }

    /** The capability profile that would be selected for a rule (for diagnostics/tests). */
    public CapabilityProfile profileFor(MockRule rule) {
        return rule.capabilityProfile();
    }

    /** Exposed for tests that need to drive the underlying compiler directly. */
    GroovyScriptCompiler compiler() {
        return compiler;
    }

    @Override
    public void close() {
        compiler.close();
    }
}
