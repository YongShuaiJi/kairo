package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockApi;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.ScriptLog;
import com.example.kairo.api.ScriptPolicyRevision;
import groovy.lang.GroovySystem;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.2 three-tier capability policy tests: EXTENDED allow-list, UNRESTRICTED execution of
 * sensitive capabilities and custom-ClassLoader business classes, shared size limits, and
 * compilation metadata accuracy.
 */
class ScriptCapabilityPolicyTest {

    private static final ScriptPolicyRevision REVISION = new ScriptPolicyRevision(7, "test-policy-hash");

    @Test
    void extendedAllowsExplicitClassAndDeniesIo() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.EXTENDED)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .allowedClasses(Set.of("com.example.biz.BizTarget"))
                .build();

        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-ext", 1, """
                    import com.example.biz.BizTarget
                    def b = new BizTarget()
                    return mock.returnValue(b.greet('world'))
                    """, ctx);

            MockDecision decision = script.execute(fakeContext());

            assertThat(decision.type()).isEqualTo(MockDecision.Type.RETURN);
            assertThat(decision.returnValue()).isEqualTo("hello world");
        }

        // java.io is not configured (and is on the hard sensitive floor): denied at usage.
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            assertThatThrownBy(() -> compiler.compile("rule-ext-io", 1,
                    "new java.io.File('/tmp/kairo-ext')", ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expression");
            // ... and denied at import time by the EXTENDED allow-list.
            assertThatThrownBy(() -> compiler.compile("rule-ext-io-import", 1,
                    "import java.io.File\nreturn mock.proceed()", ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sensitive");
        }
    }

    @Test
    void extendedRejectsUnconfiguredNonBaselineImport() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.EXTENDED)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .allowedClasses(Set.of("com.example.biz.BizTarget"))
                .build();

        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            assertThatThrownBy(() -> compiler.compile("rule-ext-unconfigured", 1,
                    "import com.example.other.Thing\nreturn mock.proceed()", ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not declared");
        }
    }

    @Test
    void extendedRejectsSensitiveConfig() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        assertThatThrownBy(() -> new ExtendedScriptPolicy(Set.of("java.io"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive");
        assertThatThrownBy(() -> new ExtendedScriptPolicy(Set.of(), Set.of("java.lang.reflect.Method")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive");
    }

    @Test
    void unrestrictedExecutesIoReflectionAndThread() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.UNRESTRICTED)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .build();

        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            // java.io: write and read a real temp file with pure java.io APIs.
            CompiledMockScript ioScript = compiler.compile("rule-urr-io", 1, """
                    def f = java.io.File.createTempFile('kairo-urr', '.txt')
                    def out = new java.io.FileOutputStream(f)
                    out.write('hello-io'.getBytes('UTF-8'))
                    out.close()
                    def bytes = new java.io.FileInputStream(f).readAllBytes()
                    f.delete()
                    return mock.returnValue(new String(bytes, 'UTF-8'))
                    """, ctx);
            assertThat(ioScript.execute(fakeContext()).returnValue()).isEqualTo("hello-io");

            // reflection: java.lang.reflect.Method.invoke
            CompiledMockScript reflectScript = compiler.compile("rule-urr-reflect", 1, """
                    def m = String.class.getMethod('length')
                    def r = m.invoke('abcde')
                    return mock.returnValue(r)
                    """, ctx);
            assertThat(reflectScript.execute(fakeContext()).returnValue()).isEqualTo(5);

            // threads + java.util.concurrent.atomic
            CompiledMockScript threadScript = compiler.compile("rule-urr-thread", 1, """
                    def ran = new java.util.concurrent.atomic.AtomicBoolean(false)
                    def t = new Thread(({ -> ran.set(true) }) as Runnable)
                    t.start()
                    t.join()
                    return mock.returnValue(ran.get())
                    """, ctx);
            assertThat(threadScript.execute(fakeContext()).returnValue()).isEqualTo(Boolean.TRUE);
        }
    }

    @Test
    void unrestrictedCompilesClassFromCustomTargetClassLoader() throws Exception {
        ClassLoader custom = compileCustomLoader("bizloader.BizLoader",
                "package bizloader; public class BizLoader { public String who() { return \"from-custom-loader\"; } }");

        // The class is NOT on the test classpath, only on the custom loader.
        assertThatThrownBy(() -> Class.forName("bizloader.BizLoader", false,
                GroovyScriptCompiler.class.getClassLoader()))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(Class.forName("bizloader.BizLoader", false, custom).getName())
                .isEqualTo("bizloader.BizLoader");

        ScriptCompilationContext ctx = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.UNRESTRICTED)
                .policyRevision(REVISION)
                .targetClassLoader(custom)
                .build();

        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(GroovyScriptCompiler.class.getClassLoader())) {
            CompiledMockScript script = compiler.compile("rule-custom-loader", 1, """
                    def b = new bizloader.BizLoader()
                    return mock.returnValue(b.who())
                    """, ctx);

            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo("from-custom-loader");
        }
    }

    @Test
    void sharedScriptByteLimitEnforcedForUnrestricted() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext tiny = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.UNRESTRICTED)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .maxScriptBytes(10)
                .build();

        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            assertThatThrownBy(() -> compiler.compile("r", 1, "return mock.proceed()", tiny))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too large");
        }
    }

    @Test
    void sharedArtifactByteLimitEnforcedForUnrestricted() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext tinyArtifact = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.UNRESTRICTED)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .maxArtifactBytes(1)
                .build();

        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            assertThatThrownBy(() -> compiler.compile("r", 1, "return mock.proceed()", tinyArtifact))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("artifact is too large");
        }
    }

    @Test
    void metadataIsAccurate() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.EXTENDED)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .allowedClasses(Set.of("com.example.biz.BizTarget"))
                .build();

        String script = """
                import com.example.biz.BizTarget
                def b = new BizTarget()
                return mock.returnValue(b.greet('meta'))
                """;

        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript compiled = compiler.compile("rule-meta", 1, script, ctx);
            assertThat(compiled).isInstanceOf(GroovyCompiledMockScript.class);
            GroovyCompilationMetadata metadata = ((GroovyCompiledMockScript) compiled).compilationMetadata();

            assertThat(metadata.scriptHash()).isEqualTo(GroovyScriptCompiler.sha256(script));
            assertThat(metadata.profile()).isEqualTo(CapabilityProfile.EXTENDED);
            assertThat(metadata.policyRevision()).isEqualTo(REVISION);
            assertThat(metadata.groovyVersion()).isEqualTo(GroovySystem.getVersion());
            assertThat(metadata.targetClassLoaderId()).isEqualTo(ctx.targetClassLoaderId());
            assertThat(metadata.artifactBytes()).isPositive();
        }
    }

    @Test
    void targetClassLoaderIdIsStable() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext safe = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.SAFE).policyRevision(REVISION)
                .targetClassLoader(loader).build();
        ScriptCompilationContext unrestricted = ScriptCompilationContext.builder()
                .profile(CapabilityProfile.UNRESTRICTED).policyRevision(REVISION)
                .targetClassLoader(loader).build();

        assertThat(safe.targetClassLoaderId()).isEqualTo(unrestricted.targetClassLoaderId());
        assertThat(safe.targetClassLoaderId()).isEqualTo(TargetClassLoaderIds.idOf(loader));
    }

    // --- EXTENDED FQN allow-list gate (closes the direct-usage bypass) -------------

    /**
     * The headline bypass: a non-sensitive, unconfigured class referenced directly via
     * FQN used to compile and run because the deny-list never names it. The independent
     * AST allow-list gate must reject it at compile time.
     */
    @Test
    void extendedAllowListBlocksConstructorFqn() {
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "new com.example.other.OtherThing()", "constructor");
    }

    @Test
    void extendedAllowListBlocksStaticReceiverFqn() {
        // A FQN static call FQN.staticMethod() is modeled by Groovy as a method call
        // whose receiver is a ClassExpression; the receiver class is gated.
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "return mock.returnValue(com.example.other.OtherThing.staticGreet())",
                "com.example.other.OtherThing");
    }

    @Test
    void extendedAllowListBlocksClassExpressionFqn() {
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "def c = com.example.other.OtherThing\nreturn mock.proceed()",
                "class expression");
    }

    @Test
    void extendedAllowListBlocksCastFqn() {
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "def x = (com.example.other.OtherThing) null\nreturn mock.proceed()", "cast");
    }

    @Test
    void extendedAllowListBlocksDeclarationFqn() {
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "com.example.other.OtherThing t = null\nreturn mock.proceed()", "declaration");
    }

    @Test
    void extendedAllowListBlocksGenericsFqn() {
        // The element type hidden inside List<...> must be gated, not just the raw List.
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "java.util.List<com.example.other.OtherThing> list = []\nreturn mock.proceed()",
                "com.example.other.OtherThing");
    }

    @Test
    void extendedAllowListBlocksArrayFqn() {
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "com.example.other.OtherThing[] arr = new com.example.other.OtherThing[0]\n"
                        + "return mock.proceed()",
                "com.example.other.OtherThing");
    }

    @Test
    void extendedAllowListBlocksAnnotationFqn() {
        // Method definitions are forbidden by the deny-list floor, so the annotation is
        // placed on a closure parameter (closures are allowed); the annotation type is
        // still gated by the AST allow-list.
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "def c = { @com.example.other.OtherAnnotation String s -> s }\nreturn mock.proceed()",
                "annotation");
    }

    /**
     * A trailing comment defeats the source-level import regex, so the import is not
     * caught by {@code validateImports}. The AST import check must still reject it.
     */
    @Test
    void extendedAllowListBlocksImportDefeatingSourceRegex() {
        assertExtendedDenied(Set.of(), Set.of("com.example.biz.BizTarget"),
                "import com.example.other.OtherThing // trailing comment defeats the source regex\n"
                        + "return mock.proceed()",
                "import");
    }

    /**
     * The sensitive floor is checked before the configured allow-list: even with a
     * too-broad package configured, a sensitive IO type referenced in a declaration is
     * denied by the AST gate (the deny-list does not inspect declaration types).
     */
    @Test
    void extendedAllowListDeniesSensitiveEvenWhenBroadPackageConfigured() {
        assertExtendedDenied(Set.of("java"), Set.of(),
                "java.io.File f = null\nreturn mock.proceed()", "sensitive");
    }

    @Test
    void extendedAllowListPermitsConfiguredClassFqn() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of(),
                Set.of("com.example.other.OtherThing"));
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-cls", 1, """
                    def t = new com.example.other.OtherThing()
                    return mock.returnValue(t.greet())
                    """, ctx);
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo("other");
        }
    }

    @Test
    void extendedAllowListPermitsConfiguredStaticReceiver() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of(),
                Set.of("com.example.other.OtherThing"));
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-static", 1, """
                    import com.example.other.OtherThing
                    return mock.returnValue(OtherThing.staticGreet())
                    """, ctx);
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo("static-other");
        }
    }

    @Test
    void extendedAllowListPermitsConfiguredPackageImportAndUsage() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of("com.example.other"), Set.of());
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-pkg", 1, """
                    import com.example.other.OtherThing
                    def t = new OtherThing()
                    return mock.returnValue(t.greet())
                    """, ctx);
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo("other");
        }
    }

    @Test
    void extendedAllowListPermitsBaselineJavaFqn() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of(),
                Set.of("com.example.biz.BizTarget"));
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-java", 1, """
                    def list = new java.util.ArrayList()
                    list.add('a')
                    return mock.returnValue(java.lang.String.valueOf(123))
                    """, ctx);
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo("123");
        }
    }

    @Test
    void extendedAllowListPermitsBaselineCast() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of(),
                Set.of("com.example.biz.BizTarget"));
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-cast", 1, """
                    def x = (java.util.List) [1, 2, 3]
                    return mock.returnValue(x.size())
                    """, ctx);
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo(3);
        }
    }

    @Test
    void extendedAllowListPermitsBaselineGenerics() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of(),
                Set.of("com.example.biz.BizTarget"));
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-gen", 1, """
                    java.util.List<String> list = ['a', 'b']
                    return mock.returnValue(list.size())
                    """, ctx);
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo(2);
        }
    }

    @Test
    void extendedAllowListPermitsBaselineArray() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of(),
                Set.of("com.example.biz.BizTarget"));
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-arr", 1, """
                    String[] arr = new String[2]
                    arr[0] = 'x'
                    return mock.returnValue(arr.length)
                    """, ctx);
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo(2);
        }
    }

    /**
     * Arrays of a configured class must be allowed: the component type is unwrapped and
     * checked, so {@code OtherThing[]} is permitted when {@code OtherThing} is configured.
     */
    @Test
    void extendedAllowListPermitsArrayOfConfiguredClass() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of(),
                Set.of("com.example.other.OtherThing"));
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-arr-cls", 1, """
                    com.example.other.OtherThing[] arr = new com.example.other.OtherThing[1]
                    return mock.returnValue(arr.length)
                    """, ctx);
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo(1);
        }
    }

    @Test
    void extendedAllowListPermitsClosureWithBaselineParam() {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, Set.of(),
                Set.of("com.example.biz.BizTarget"));
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            CompiledMockScript script = compiler.compile("rule-allow-closure", 1, """
                    def c = { String s -> s.toUpperCase() }
                    return mock.proceed()
                    """, ctx);
            assertThat(script.execute(fakeContext()).type()).isEqualTo(MockDecision.Type.PROCEED);
        }
    }

    private static ScriptCompilationContext extendedCtx(ClassLoader loader,
                                                        Set<String> packages, Set<String> classes) {
        return ScriptCompilationContext.builder()
                .profile(CapabilityProfile.EXTENDED)
                .policyRevision(REVISION)
                .targetClassLoader(loader)
                .allowedPackages(packages)
                .allowedClasses(classes)
                .build();
    }

    private static void assertExtendedDenied(Set<String> packages, Set<String> classes,
                                             String script, String messageFragment) {
        ClassLoader loader = GroovyScriptCompiler.class.getClassLoader();
        ScriptCompilationContext ctx = extendedCtx(loader, packages, classes);
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler(loader)) {
            assertThatThrownBy(() -> compiler.compile("rule-deny", 1, script, ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(messageFragment);
        }
    }

    private static InvocationContext fakeContext() {
        try {
            return new FakeInvocationContext();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClassLoader compileCustomLoader(String fqcn, String source) throws Exception {
        Path tmp = Files.createTempDirectory("kairo-urr-custom-");
        Path src = tmp.resolve(fqcn.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("system java compiler available").isNotNull();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tmp.toFile()));
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics,
                    null, null, fm.getJavaFileObjects(src.toFile()));
            boolean ok = task.call();
            assertThat(ok).as(() -> "java compile failed: " + diagnostics.getDiagnostics()).isTrue();
        }
        return new URLClassLoader(new URL[]{ tmp.toUri().toURL() },
                GroovyScriptCompiler.class.getClassLoader());
    }

    private static final class FakeInvocationContext implements InvocationContext {
        private final Object[] arguments;
        private final MethodMetadata method;
        private final MockApi mockApi = new RecordingMockApi();

        private FakeInvocationContext() throws Exception {
            this.arguments = new Object[0];
            Method reflectMethod = String.class.getMethod("length");
            this.method = new MethodMetadata(reflectMethod, "()I");
        }

        @Override public InvokePhase phase() { return InvokePhase.BEFORE; }
        @Override public Object[] arguments() { return arguments; }
        @Override public Object target() { return null; }
        @Override public Object result() { return null; }
        @Override public Throwable throwable() { return null; }
        @Override public MethodMetadata method() { return method; }
        @Override public MockApi mockApi() { return mockApi; }
        @Override public ScriptLog log() { return ScriptLog.NOOP; }
    }

    private static final class RecordingMockApi implements MockApi {
        @Override public MockDecision proceed() { return MockDecision.proceed(); }
        @Override public MockDecision proceed(Object[] arguments) { return MockDecision.proceed(arguments); }
        @Override public MockDecision returnValue(Object value) { return MockDecision.returnValue(value); }
        @Override public MockDecision returnJson(String json) { throw new UnsupportedOperationException(); }
        @Override public MockDecision throwException(Throwable throwable) { return MockDecision.throwException(throwable); }
        @Override public MockDecision throwException(String exceptionClassName, String message) { throw new UnsupportedOperationException(); }
        @Override public Object newReturnObject() { throw new UnsupportedOperationException(); }
        @Override public Object fromJson(String json, Class<?> targetType) { throw new UnsupportedOperationException(); }
        @Override public Object get(Object target, String propertyPath) { throw new UnsupportedOperationException(); }
        @Override public void set(Object target, String propertyPath, Object value) { throw new UnsupportedOperationException(); }
        @Override public boolean isType(Object target, String className) { return false; }
    }
}
