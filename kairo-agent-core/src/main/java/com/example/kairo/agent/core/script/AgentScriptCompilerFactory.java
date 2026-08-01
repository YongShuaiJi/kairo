package com.example.kairo.agent.core.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.groovy.CompiledMockScript;
import com.example.kairo.groovy.GroovyScriptCompiler;
import com.example.kairo.groovy.ScriptCompilationContext;
import com.example.kairo.groovy.ScriptCompilerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * ClassLoader-aware {@link ScriptCompilerFactory} owned by the agent runtime.
 *
 * <p>Each compilation is bound to a small dual-delegating parent ({@link DualDelegatingClassLoader})
 * that bridges the ClassLoader which defined the target method with the agent's own ClassLoader, so
 * a script resolves BOTH business types that are only visible to the target loader AND agent-owned
 * Kairo/Groovy/API types. This matters for a real, isolated agent: the application loader that owns
 * the target method cannot see the agent's Kairo/Groovy classes, so handing it straight to Groovy
 * as the compilation parent (as V1.6 did) made generated scripts fail to resolve
 * {@link com.example.kairo.groovy.KairoScript}. The bridge never defines classes, and the stable
 * loader id is still taken from {@link ClassLoaderIdentity#idOf(ClassLoader)} of the real target
 * loader (never from the bridge) to stay consistent with the rest of the agent (method keys,
 * instrumentation registry, bytecode identity) and with the weak-reference cache. Discarding a
 * business ClassLoader therefore still does not pin it.
 *
 * <p>For target methods defined by the bootstrap loader (e.g. JDK classes), there are no business
 * types to resolve, so the agent's own ClassLoader is used directly as the compilation parent and
 * the canonical {@code "bootstrap"} id is recorded for cache keying.
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
        ClassLoader compileParent = compilationParent(targetLoader);
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
        ClassLoader compileParent = compilationParent(targetLoader);
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
        ClassLoader compileParent = compilationParent(targetLoader);
        ScriptCompilationContext context = ScriptCompilationContext.builder()
                .profile(profile)
                .policyRevision(revision)
                .targetClassLoader(compileParent)
                .targetClassLoaderId(targetClassLoaderId)
                .build();
        return compiler.compile("compile-" + Integer.toHexString(script.hashCode()), 1L, script, context);
    }

    /**
     * Choose the Groovy compilation parent for a target ClassLoader. A non-null target
     * (an application/business loader, possibly isolated from the agent's own classes) is
     * wrapped in a {@link DualDelegatingClassLoader} so a script resolves agent-owned
     * Kairo/Groovy/API types from the agent ClassLoader while still resolving business
     * types from the target. A {@code null} (bootstrap) target defines no business types,
     * so the agent ClassLoader is used directly, exactly as before.
     *
     * <p>The recorded target ClassLoader id is unaffected: callers still key the cache on
     * {@link ClassLoaderIdentity#idOf(ClassLoader)} of the real target, never on this bridge.
     */
    private ClassLoader compilationParent(ClassLoader targetLoader) {
        return targetLoader != null
                ? new DualDelegatingClassLoader(agentClassLoader, targetLoader)
                : agentClassLoader;
    }

    /** The capability profile that would be selected for a rule (for diagnostics/tests). */
    public CapabilityProfile profileFor(MockRule rule) {
        return rule.capabilityProfile();
    }

    /** Exposed for tests that need to drive the underlying compiler directly. */
    GroovyScriptCompiler compiler() {
        return compiler;
    }

    /**
     * V1.5 &sect;3.2: drop every cached compiled script and generation bound to
     * the collected loader id. Delegates to the underlying weak-reference cache;
     * see {@link GroovyScriptCompiler#clearForLoader(String)}.
     */
    public int clearForLoader(String classLoaderId) {
        return compiler.clearForLoader(classLoaderId);
    }

    @Override
    public void close() {
        compiler.close();
    }

    /**
     * Compilation-only ClassLoader that lets a Groovy script resolve BOTH business types
     * visible only to an isolated target ClassLoader AND agent-owned Kairo/Groovy/API
     * types, without either side shadowing the other. It never defines classes: it only
     * delegates {@code loadClass}, so the generated-script defining loader, the weak
     * cache, and the unload/leak lifecycle in {@link GroovyScriptCompiler} are unchanged.
     *
     * <p>Agent-owned namespaces ({@code com.example.kairo.*}, {@code groovy.*},
     * {@code org.codehaus.groovy.*}, {@code org.apache.groovy.*}) are resolved exclusively
     * from the agent ClassLoader. This guarantees their {@code Class} identity is exactly
     * the agent's &mdash; the Groovy compiler itself is loaded by the agent, so the bytecode
     * it generates must reference the agent's {@code groovy.lang.GroovyObject}/{@link
     * com.example.kairo.groovy.KairoScript} &mdash; and a same-name application class on the
     * target's classpath can never shadow them. Every other name is resolved from the
     * target ClassLoader first (so business types keep target identity for {@code instanceof}
     * at run time), falling back to the agent ClassLoader for shared platform types a thin
     * business loader (whose parent is the bootstrap loader) cannot see by itself.
     *
     * <p>Resources are delegated to both loaders (target first, then agent) so Groovy's
     * extension-module and classpath-resource lookups still see agent-owned Groovy
     * metadata in the isolated-agent case where the target cannot.
     */
    private static final class DualDelegatingClassLoader extends ClassLoader {

        private final ClassLoader agentLoader;
        private final ClassLoader targetLoader;

        DualDelegatingClassLoader(ClassLoader agentLoader, ClassLoader targetLoader) {
            super(null);
            this.agentLoader = Objects.requireNonNull(agentLoader, "agentLoader");
            this.targetLoader = Objects.requireNonNull(targetLoader, "targetLoader");
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (isAgentOwned(name)) {
                        try {
                            // Exact agent types win when both sides expose the same name.
                            loaded = agentLoader.loadClass(name);
                        } catch (ClassNotFoundException agentMissed) {
                            // The namespace prefix is intentionally broader than the set of
                            // classes shipped by this agent. Do not make an application's own
                            // com.example.kairo.* business classes unreachable merely because
                            // the agent does not define that particular name.
                            try {
                                loaded = targetLoader.loadClass(name);
                            } catch (ClassNotFoundException targetMissed) {
                                targetMissed.addSuppressed(agentMissed);
                                throw targetMissed;
                            }
                        }
                    } else {
                        try {
                            loaded = targetLoader.loadClass(name);
                        } catch (ClassNotFoundException targetMissed) {
                            loaded = agentLoader.loadClass(name);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        public URL getResource(String name) {
            URL resource = targetLoader.getResource(name);
            return resource != null ? resource : agentLoader.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            List<URL> combined = new ArrayList<>();
            collectResources(combined, targetLoader.getResources(name));
            collectResources(combined, agentLoader.getResources(name));
            return Collections.enumeration(combined);
        }

        private static void collectResources(List<URL> sink, Enumeration<URL> source) throws IOException {
            while (source.hasMoreElements()) {
                URL url = source.nextElement();
                if (!sink.contains(url)) {
                    sink.add(url);
                }
            }
        }

        private static boolean isAgentOwned(String name) {
            return name.startsWith("com.example.kairo.")
                    || name.startsWith("groovy.")
                    || name.startsWith("org.codehaus.groovy.")
                    || name.startsWith("org.apache.groovy.");
        }
    }
}
