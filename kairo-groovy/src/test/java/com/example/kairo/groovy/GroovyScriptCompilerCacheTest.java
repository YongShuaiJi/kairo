package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.ScriptPolicyRevision;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the weak-reference compilation cache lifecycle: a compiler that stays alive must not
 * pin a target ClassLoader that the application has already discarded, and the cache must still
 * deduplicate a compiled script while something holds it.
 */
class GroovyScriptCompilerCacheTest {

    private static final ScriptPolicyRevision REVISION = new ScriptPolicyRevision(1, "test");

    @Test
    void weakCacheDoesNotPinTargetClassLoader() throws Exception {
        // The target loader is a plain URLClassLoader (GC-able once unreferenced) standing in
        // for an application business ClassLoader. The script does not reference any
        // loader-private type, so Groovy's global ClassInfo/MetaClass cache (a separate,
        // Groovy-internal concern outside the compilation cache) is not populated and cannot
        // pin the compiled class.
        ClassLoader target = new URLClassLoader(new URL[0], GroovyScriptCompiler.class.getClassLoader());
        WeakReference<ClassLoader> targetRef = new WeakReference<>(target);

        GroovyScriptCompiler compiler = new GroovyScriptCompiler(GroovyScriptCompiler.class.getClassLoader());
        ScriptCompilationContext ctx = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.UNRESTRICTED)
                .policyRevision(REVISION)
                .targetClassLoader(target)
                .build();
        CompiledMockScript script = compiler.compile("leak-rule", 1, "return mock.proceed()", ctx);
        WeakReference<CompiledMockScript> scriptRef = new WeakReference<>(script);
        assertThat(script).isNotNull();

        // Drop the only strong references to the compiled script and the target loader. The
        // cache holds the script (and the per-loader generation) weakly, so both must become
        // reclaimable while the compiler itself stays alive. The compiler is deliberately kept
        // open across GC to prove the weak cache (not close()) is what releases the loader.
        // The context must also be dropped: it strongly references the target ClassLoader.
        script = null;
        target = null;
        ctx = null;

        assertThat(forceGc(scriptRef))
                .as("compiled script should be reclaimable once no rule holds it")
                .isTrue();
        assertThat(forceGc(targetRef))
                .as("target ClassLoader should be reclaimable once no rule holds the compiled script")
                .isTrue();
        compiler.close();
    }

    @Test
    void cacheDeduplicatesWhileScriptIsHeld() throws Exception {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.SAFE)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .build();
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript first = compiler.compile("dedup-rule", 1, "return mock.proceed()", ctx);
            CompiledMockScript second = compiler.compile("dedup-rule", 1, "return mock.proceed()", ctx);

            assertThat(second).isSameAs(first);
        }
    }

    @Test
    void cacheRecompilesAfterScriptIsReclaimed() throws Exception {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.SAFE)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .build();
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript first = compiler.compile("reclaim-rule", 1, "return mock.proceed()", ctx);
            WeakReference<CompiledMockScript> firstRef = new WeakReference<>(first);
            first = null;
            assertThat(forceGc(firstRef)).as("compiled script should be reclaimable").isTrue();

            // After GC, the weak cache entry is stale; a fresh compile produces a new instance.
            CompiledMockScript second = compiler.compile("reclaim-rule", 1, "return mock.proceed()", ctx);
            assertThat(second).isNotSameAs(firstRef.get());
            assertThat(second.execute(fakeContext()).type()).isEqualTo(MockDecision.Type.PROCEED);
        }
    }

    @Test
    void policyRevisionIsPartOfTheCacheKey() throws Exception {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptPolicyRevision rev1 = new ScriptPolicyRevision(1, "hash-1");
        ScriptPolicyRevision rev2 = new ScriptPolicyRevision(2, "hash-2");
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            ScriptCompilationContext ctx1 = ScriptCompilationContext.builder()
                    .profile(CapabilityProfile.SAFE)
                    .policyRevision(rev1)
                    .targetClassLoader(loader)
                    .build();
            ScriptCompilationContext ctx2 = ScriptCompilationContext.builder()
                    .profile(CapabilityProfile.SAFE)
                    .policyRevision(rev2)
                    .targetClassLoader(loader)
                    .build();

            CompiledMockScript first = compiler.compile("revision-rule", 1, "return mock.proceed()", ctx1);
            // Same revision deduplicates while the script is held.
            CompiledMockScript firstAgain = compiler.compile("revision-rule", 1, "return mock.proceed()", ctx1);
            assertThat(firstAgain).isSameAs(first);
            assertThat(((GroovyCompiledMockScript) first).compilationMetadata().policyRevision()).isEqualTo(rev1);

            // A different policy revision is a different cache key: a fresh compilation with the
            // new revision recorded, even though the script source, profile and loader match.
            CompiledMockScript second = compiler.compile("revision-rule", 1, "return mock.proceed()", ctx2);
            assertThat(second).isNotSameAs(first);
            assertThat(((GroovyCompiledMockScript) second).compilationMetadata().policyRevision()).isEqualTo(rev2);
        }
    }

    @Test
    void policyRevisionRebuildsGenerationWithNewAllowList() throws Exception {
        // The script references a non-baseline, non-sensitive business type inline (no import, so
        // validateSource's import gate does not fire first); the EXTENDED AST allow-list gate that
        // runs during parseClass is what admits or rejects it. That gate lives on the per-revision
        // generation, so withdrawing the allow-list under a new revision must rebuild the
        // generation and reject the now-forbidden reference.
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        String script = "def b = new com.example.biz.BizTarget()\n"
                + "return mock.returnValue(b.greet('rev'))";
        ScriptPolicyRevision rev1 = new ScriptPolicyRevision(1, "hash-1");
        ScriptPolicyRevision rev2 = new ScriptPolicyRevision(2, "hash-2");
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            ScriptCompilationContext allowBiz = ScriptCompilationContext.builder()
                    .profile(CapabilityProfile.EXTENDED)
                    .policyRevision(rev1)
                    .targetClassLoader(loader)
                    .allowedClasses(Set.of("com.example.biz.BizTarget"))
                    .build();
            CompiledMockScript first = compiler.compile("gen-rule", 1, script, allowBiz);
            assertThat(first.execute(fakeContext()).returnValue()).isEqualTo("hello rev");

            // Holding the first script keeps its generation alive. Under the old (revision-less)
            // generation key this second compile would reuse that generation and silently admit the
            // import; with revision in the key it builds a fresh empty-allow-list generation and
            // rejects the reference at compile time.
            ScriptCompilationContext denyBiz = ScriptCompilationContext.builder()
                    .profile(CapabilityProfile.EXTENDED)
                    .policyRevision(rev2)
                    .targetClassLoader(loader)
                    .allowedClasses(Set.of())
                    .build();
            assertThatThrownBy(() -> compiler.compile("gen-rule", 1, script, denyBiz))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Loop System.gc() until the referent is reclaimed or the budget is exhausted. */
    private static boolean forceGc(WeakReference<?> reference) throws InterruptedException {
        // URLClassLoader (and the GroovyClassLoader it parents) are heavier than plain objects,
        // so this needs several full-GC cycles plus finalization and a memory-pressure nudge.
        for (int i = 0; i < 40; i++) {
            if (reference.get() == null) {
                return true;
            }
            System.gc();
            System.runFinalization();
            Thread.sleep(100L);
            // Allocate to encourage collection of old-gen references.
            byte[] sink = new byte[256 * 1024];
        }
        return reference.get() == null;
    }

    private static InvocationContext fakeContext() {
        try {
            return new FakeInvocationContext();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeInvocationContext implements InvocationContext {
        private final Object[] arguments;
        private final com.example.kairo.api.MethodMetadata method;

        private FakeInvocationContext() throws Exception {
            this.arguments = new Object[0];
            java.lang.reflect.Method reflectMethod = String.class.getMethod("length");
            this.method = new com.example.kairo.api.MethodMetadata(reflectMethod, "()I");
        }

        @Override public com.example.kairo.api.InvokePhase phase() { return com.example.kairo.api.InvokePhase.BEFORE; }
        @Override public Object[] arguments() { return arguments; }
        @Override public Object target() { return null; }
        @Override public Object result() { return null; }
        @Override public Throwable throwable() { return null; }
        @Override public com.example.kairo.api.MethodMetadata method() { return method; }
        @Override public com.example.kairo.api.MockApi mockApi() { return new RecordingMockApi(); }
        @Override public com.example.kairo.api.ScriptLog log() { return com.example.kairo.api.ScriptLog.NOOP; }
    }

    private static final class RecordingMockApi implements com.example.kairo.api.MockApi {
        @Override public MockDecision proceed() { return MockDecision.proceed(); }
        @Override public MockDecision proceed(Object[] arguments) { return MockDecision.proceed(arguments); }
        @Override public MockDecision returnValue(Object value) { return MockDecision.returnValue(value); }
        @Override public MockDecision returnJson(String json) { return MockDecision.returnValue(json); }
        @Override public MockDecision throwException(Throwable throwable) { return MockDecision.throwException(throwable); }
        @Override public MockDecision throwException(String exceptionClassName, String message) {
            return MockDecision.throwException(new IllegalStateException(exceptionClassName + ": " + message));
        }
        @Override public Object newReturnObject() { return null; }
        @Override public Object fromJson(String json, Class<?> targetType) { return null; }
        @Override public Object get(Object target, String propertyPath) { return null; }
        @Override public void set(Object target, String propertyPath, Object value) { }
        @Override public boolean isType(Object target, String className) { return false; }
    }
}
