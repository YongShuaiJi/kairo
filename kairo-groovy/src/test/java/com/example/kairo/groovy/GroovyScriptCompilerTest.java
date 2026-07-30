package com.example.kairo.groovy;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodMetadata;
import com.example.kairo.api.MockApi;
import com.example.kairo.api.MockDecision;
import com.example.kairo.api.ScriptLog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroovyScriptCompilerTest {

    @Test
    void compilesAndExecutesKairoScript() throws Exception {
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            CompiledMockScript script = compiler.compile("rule-1", 1, """
                    if (args[0] == 'U100') {
                        return mock.returnValue('mocked')
                    }
                    return mock.proceed()
                    """);

            MockDecision decision = script.execute(new FakeInvocationContext(new Object[]{"U100"}));

            assertThat(decision.type()).isEqualTo(MockDecision.Type.RETURN);
            assertThat(decision.returnValue()).isEqualTo("mocked");
        }
    }

    @Test
    void classicCallSitePreservesReplacementArgumentArray() throws Exception {
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            CompiledMockScript script = compiler.compile("replace-args", 1, """
                    def newArgs = args.clone()
                    newArgs[0] = 'changed'
                    return mock.proceed(newArgs)
                    """);

            MockDecision decision = script.execute(
                    new FakeInvocationContext(new Object[]{"original"}));

            assertThat(decision.arguments()).containsExactly("changed");
        }
    }

    @Test
    void rejectsClearlyDangerousScriptOnSave() {
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            assertThatThrownBy(() -> compiler.compile("rule-1", 1, "java.lang.System.exit(0)"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expression");
        }
    }

    @Test
    void rejectsReflectionAccessOnSave() {
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            assertThatThrownBy(() -> compiler.compile("rule-1", 1, "Class.forName('java.lang.System')"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expression");
        }
    }

    @Test
    void rejectsProcessAndFileConstructionOnSave() {
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            assertThatThrownBy(() -> compiler.compile("rule-1", 1, "new java.io.File('/tmp/kairo')"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expression");
            assertThatThrownBy(() -> compiler.compile("rule-1", 1, "new ProcessBuilder('sh', '-c', 'date')"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expression");
        }
    }

    @Test
    void rejectsLoopsAndPackageDeclarationsOnSave() {
        try (GroovyScriptCompiler compiler = new GroovyScriptCompiler()) {
            assertThatThrownBy(() -> compiler.compile("rule-1", 1, "while (true) { mock.proceed() }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Statement");
            assertThatThrownBy(() -> compiler.compile("rule-1", 1, "package bad\nreturn mock.proceed()"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Forbidden Groovy source marker");
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
        private final MockApi mockApi = new MockApi() {
            @Override
            public MockDecision proceed() {
                return MockDecision.proceed();
            }

            @Override
            public MockDecision proceed(Object[] arguments) {
                return MockDecision.proceed(arguments);
            }

            @Override
            public MockDecision returnValue(Object value) {
                return MockDecision.returnValue(value);
            }

            @Override
            public MockDecision returnJson(String json) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MockDecision throwException(Throwable throwable) {
                return MockDecision.throwException(throwable);
            }

            @Override
            public MockDecision throwException(String exceptionClassName, String message) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object newReturnObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object fromJson(String json, Class<?> targetType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object get(Object target, String propertyPath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void set(Object target, String propertyPath, Object value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isType(Object target, String className) {
                return false;
            }
        };

        private FakeInvocationContext(Object[] arguments) throws Exception {
            this.arguments = arguments;
            Method reflectMethod = Target.class.getMethod("echo", String.class);
            this.method = new MethodMetadata(reflectMethod, "(Ljava/lang/String;)Ljava/lang/String;");
        }

        @Override
        public InvokePhase phase() {
            return InvokePhase.BEFORE;
        }

        @Override
        public Object[] arguments() {
            return arguments;
        }

        @Override
        public Object target() {
            return null;
        }

        @Override
        public Object result() {
            return null;
        }

        @Override
        public Throwable throwable() {
            return null;
        }

        @Override
        public MethodMetadata method() {
            return method;
        }

        @Override
        public MockApi mockApi() {
            return mockApi;
        }

        @Override
        public ScriptLog log() {
            return ScriptLog.NOOP;
        }
    }
}
