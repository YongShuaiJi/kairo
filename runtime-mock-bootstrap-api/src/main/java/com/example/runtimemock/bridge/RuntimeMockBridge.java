package com.example.runtimemock.bridge;

public final class RuntimeMockBridge {

    private static volatile BridgeDispatcher dispatcher = BridgeDispatcher.NOOP;

    private RuntimeMockBridge() {
    }

    public static void install(BridgeDispatcher newDispatcher) {
        dispatcher = newDispatcher == null ? BridgeDispatcher.NOOP : newDispatcher;
    }

    public static void uninstall() {
        dispatcher = BridgeDispatcher.NOOP;
    }

    public static EnterResult enter(
            Class<?> declaringClass,
            java.lang.reflect.Method method,
            Object target,
            Object[] arguments
    ) {
        try {
            return dispatcher.onEnter(declaringClass, method, target, arguments);
        } catch (Throwable ignored) {
            return EnterResult.proceedWithoutContext();
        }
    }

    public static ExitResult exit(
            Object invocationToken,
            Object returnValue,
            Throwable throwable
    ) {
        if (invocationToken == null) {
            return ExitResult.proceed();
        }

        try {
            return dispatcher.onExit(invocationToken, returnValue, throwable);
        } catch (Throwable ignored) {
            return ExitResult.proceed();
        }
    }
}
