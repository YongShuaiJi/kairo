package com.example.runtimemock.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMockBridgeTest {

    @AfterEach
    void tearDown() {
        RuntimeMockBridge.uninstall();
    }

    @Test
    void bridgeFailsOpenWhenDispatcherThrows() throws Exception {
        RuntimeMockBridge.install(new BridgeDispatcher() {
            @Override
            public EnterResult onEnter(Class<?> declaringClass, java.lang.reflect.Method method,
                                       Object target, Object[] arguments) {
                throw new IllegalStateException("boom");
            }

            @Override
            public ExitResult onExit(Object invocationToken, Object returnValue, Throwable throwable) {
                throw new IllegalStateException("boom");
            }
        });

        EnterResult enter = RuntimeMockBridge.enter(String.class,
                String.class.getMethod("substring", int.class), "abc", new Object[]{1});
        ExitResult exit = RuntimeMockBridge.exit(new Object(), "abc", null);

        assertThat(enter.isSkipOriginalMethod()).isFalse();
        assertThat(enter.getInvocationToken()).isNull();
        assertThat(exit.getAction()).isEqualTo(BridgeAction.PROCEED);
    }

    @Test
    void bridgeDelegatesToInstalledDispatcher() throws Exception {
        Object token = new Object();
        RuntimeMockBridge.install(new BridgeDispatcher() {
            @Override
            public EnterResult onEnter(Class<?> declaringClass, java.lang.reflect.Method method,
                                       Object target, Object[] arguments) {
                return EnterResult.proceed(token, new Object[]{"changed"});
            }

            @Override
            public ExitResult onExit(Object invocationToken, Object returnValue, Throwable throwable) {
                return ExitResult.returnValue("mocked");
            }
        });

        EnterResult enter = RuntimeMockBridge.enter(String.class,
                String.class.getMethod("substring", int.class), "abc", new Object[]{1});
        ExitResult exit = RuntimeMockBridge.exit(token, "origin", null);

        assertThat(enter.getInvocationToken()).isSameAs(token);
        assertThat(enter.getArguments()).containsExactly("changed");
        assertThat(exit.getAction()).isEqualTo(BridgeAction.RETURN);
        assertThat(exit.getReturnValue()).isEqualTo("mocked");
    }
}
