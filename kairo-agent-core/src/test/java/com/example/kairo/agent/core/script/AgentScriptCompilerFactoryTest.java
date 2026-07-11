package com.example.kairo.agent.core.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockApi;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.MockRule;
import com.example.kairo.api.ScriptLog;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.groovy.CompiledMockScript;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the ClassLoader-aware {@link AgentScriptCompilerFactory}: a script compiled for a
 * target method can reference business types visible only to that method's ClassLoader, and the
 * legacy SAFE-defaults compile path still works for the HTTP console.
 */
class AgentScriptCompilerFactoryTest {

    private static final ScriptPolicyRevision REVISION = new ScriptPolicyRevision(1, "test");

    @Test
    void compilesScriptAgainstTargetMethodClassLoader() throws Exception {
        ClassLoader businessLoader = compileBusinessClass("biz.BizEcho",
                "package biz; public class BizEcho { public String echo(String s) { return \"echo:\" + s; } }");
        Class<?> bizEcho = Class.forName("biz.BizEcho", false, businessLoader);
        Method echo = bizEcho.getMethod("echo", String.class);

        try (AgentScriptCompilerFactory factory = new AgentScriptCompilerFactory(
                AgentScriptCompilerFactory.class.getClassLoader())) {
            MockRule rule = MockRule.builder()
                    .id("biz-rule")
                    .target(new MethodSelector(bizEcho.getName(),
                            ClassLoaderIdentity.idOf(bizEcho.getClassLoader()),
                            echo.getName(), "()Ljava/lang/String;"))
                    .phase(InvokePhase.BEFORE)
                    .script("def e = new biz.BizEcho()\nreturn mock.returnValue(e.echo('target-dto'))")
                    .capabilityProfile(CapabilityProfile.UNRESTRICTED)
                    .policyRevision(REVISION)
                    .build();

            CompiledMockScript script = factory.compile(echo, rule);

            // The business type is not visible to the agent's own ClassLoader, proving the
            // script was compiled against the target method's ClassLoader.
            assertThatThrownByClassNotFound("biz.BizEcho",
                    AgentScriptCompilerFactory.class.getClassLoader());
            assertThat(script.execute(fakeContext()).returnValue()).isEqualTo("echo:target-dto");
        }
    }

    @Test
    void legacySafeCompilePathUsesAgentClassLoader() throws Exception {
        try (AgentScriptCompilerFactory factory = new AgentScriptCompilerFactory(
                AgentScriptCompilerFactory.class.getClassLoader())) {
            CompiledMockScript script = factory.compileScript("legacy", 1, "return mock.proceed()");

            assertThat(script).isNotNull();
            assertThat(script.execute(fakeContext()).type()).isEqualTo(MockDecision.Type.PROCEED);
        }
    }

    @Test
    void bootstrapTargetMethodFallsBackToAgentClassLoader() throws Exception {
        // A JDK method whose declaring class is loaded by the bootstrap loader (null). The
        // factory must fall back to the agent ClassLoader so Kairo script types resolve.
        Method length = String.class.getMethod("length");
        try (AgentScriptCompilerFactory factory = new AgentScriptCompilerFactory(
                AgentScriptCompilerFactory.class.getClassLoader())) {
            MockRule rule = MockRule.builder()
                    .id("jdk-rule")
                    .target(new MethodSelector(String.class.getName(),
                            ClassLoaderIdentity.idOf(String.class.getClassLoader()),
                            length.getName(), "()I"))
                    .phase(InvokePhase.BEFORE)
                    .script("return mock.proceed()")
                    .capabilityProfile(CapabilityProfile.SAFE)
                    .build();

            CompiledMockScript script = factory.compile(length, rule);
            assertThat(script.execute(fakeContext()).type()).isEqualTo(MockDecision.Type.PROCEED);
        }
    }

    @Test
    void legacyRuleWithoutProfileDefaultsToSafe() throws Exception {
        // A V1.0 rule carries no capability profile and no policy revision. The factory must
        // treat it as SAFE: an ordinary script compiles and runs, while a SAFE-forbidden
        // capability is rejected at compile time.
        Method length = String.class.getMethod("length");
        MethodSelector target = new MethodSelector(String.class.getName(),
                ClassLoaderIdentity.idOf(String.class.getClassLoader()),
                length.getName(), "()I");
        try (AgentScriptCompilerFactory factory = new AgentScriptCompilerFactory(
                AgentScriptCompilerFactory.class.getClassLoader())) {
            MockRule safeRule = MockRule.builder()
                    .id("legacy-safe")
                    .target(target)
                    .phase(InvokePhase.BEFORE)
                    .script("return mock.proceed()")
                    .build();
            CompiledMockScript safe = factory.compile(length, safeRule);
            assertThat(safe.execute(fakeContext()).type()).isEqualTo(MockDecision.Type.PROCEED);

            MockRule forbiddenRule = MockRule.builder()
                    .id("legacy-forbidden")
                    .target(target)
                    .phase(InvokePhase.BEFORE)
                    .script("new java.io.File('/tmp/kairo-legacy-safe')")
                    .build();
            assertThatThrownBy(() -> factory.compile(length, forbiddenRule))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void assertThatThrownByClassNotFound(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            throw new AssertionError("expected ClassNotFoundException for " + name
                    + " on " + loader);
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }

    private static InvocationContext fakeContext() {
        try {
            return new FakeInvocationContext();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClassLoader compileBusinessClass(String fqcn, String source) throws Exception {
        Path tmp = Files.createTempDirectory("kairo-factory-");
        Path src = tmp.resolve(fqcn.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source, StandardCharsets.UTF_8);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fm.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(tmp.toFile()));
            var task = compiler.getTask(null, fm, null, null, null,
                    fm.getJavaFileObjects(src.toFile()));
            boolean ok = task.call();
            assertThat(ok).as("java compile failed").isTrue();
        }
        return new URLClassLoader(new URL[]{ tmp.toUri().toURL() },
                AgentScriptCompilerFactory.class.getClassLoader());
    }

    private static final class FakeInvocationContext implements InvocationContext {
        private final Object[] arguments;
        private final MethodMetadata method;

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
        @Override public MockApi mockApi() { return new RecordingMockApi(); }
        @Override public ScriptLog log() { return ScriptLog.NOOP; }
    }

    private static final class RecordingMockApi implements MockApi {
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
