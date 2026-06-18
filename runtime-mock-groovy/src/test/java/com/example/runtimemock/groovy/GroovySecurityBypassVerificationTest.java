package com.example.runtimemock.groovy;

import com.example.runtimemock.api.InvocationContext;
import com.example.runtimemock.api.InvokePhase;
import com.example.runtimemock.api.MethodMetadata;
import com.example.runtimemock.api.MockApi;
import com.example.runtimemock.api.MockDecision;
import com.example.runtimemock.api.ScriptLog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for previously confirmed Groovy sandbox bypasses.
 */
class GroovySecurityBypassVerificationTest {

    @Test
    void groovyShellBypassesSecurityPolicy() throws Exception {
        // 预期：此脚本应被拒绝；若编译通过且执行成功，则证明 SecureASTCustomizer 黑名单
        // 未覆盖 groovy.lang.GroovyShell，沙箱被绕过。
        String script = """
                def shell = new groovy.lang.GroovyShell()
                shell.evaluate("1+1")
                return mock.proceed()
                """;
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            boolean compiled;
            try {
                CompiledMockScript ignored = compiler.compile("rule-shell", 1, script);
                compiled = true;
            } catch (IllegalArgumentException ex) {
                compiled = false;
            }
            assertThat(compiled)
                    .as("GroovyShell must be rejected")
                    .isFalse();
        }
    }

    @Test
    void groovyClassLoaderParseClassBypassesSecurityPolicy() throws Exception {
        String script = """
                def loader = new groovy.lang.GroovyClassLoader()
                loader.parseClass("System.exit(0)")
                return mock.proceed()
                """;
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            boolean compiled;
            try {
                CompiledMockScript ignored = compiler.compile("rule-gcl", 1, script);
                compiled = true;
            } catch (IllegalArgumentException ex) {
                compiled = false;
            }
            assertThat(compiled)
                    .as("GroovyClassLoader.parseClass must be rejected")
                    .isFalse();
        }
    }

    @Test
    void instrumentationImportNotBlocked() throws Exception {
        // B5: 脚本不得访问 Instrumentation/AgentBuilder/ClassFileTransformer
        String script = """
                def t = java.lang.instrument.ClassFileTransformer.class
                return mock.proceed()
                """;
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            boolean compiled;
            try {
                CompiledMockScript ignored = compiler.compile("rule-instr", 1, script);
                compiled = true;
            } catch (IllegalArgumentException ex) {
                compiled = false;
            }
            assertThat(compiled)
                    .as("java.lang.instrument.ClassFileTransformer must be rejected")
                    .isFalse();
        }
    }

    @Test
    void deepClosureNestingNotLimited() throws Exception {
        // B3: 闭包嵌套深度不超过 5
        StringBuilder sb = new StringBuilder("return ");
        for (int i = 0; i < 10; i++) sb.append("{ ");
        sb.append("mock.proceed() ");
        for (int i = 0; i < 10; i++) sb.append("} ");
        String script = sb.toString();
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            boolean compiled;
            try {
                CompiledMockScript ignored = compiler.compile("rule-nest", 1, script);
                compiled = true;
            } catch (IllegalArgumentException ex) {
                compiled = false;
            }
            assertThat(compiled)
                    .as("Closure nesting deeper than five must be rejected")
                    .isFalse();
        }
    }

    public static final class Target {
        public String echo(String value) {
            return value;
        }
    }

    private static final class FakeInvocationContext implements InvocationContext {
        private final Object[] arguments;
        private final MethodMetadata method;
        private final MockApi mockApi = new RecordingMockApi();

        private FakeInvocationContext(Object[] arguments) throws Exception {
            this.arguments = arguments;
            Method reflectMethod = Target.class.getMethod("echo", String.class);
            this.method = new MethodMetadata(reflectMethod, "(Ljava/lang/String;)Ljava/lang/String;");
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
