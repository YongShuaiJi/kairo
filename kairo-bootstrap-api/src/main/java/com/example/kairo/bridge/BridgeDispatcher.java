package com.example.kairo.bridge;

public interface BridgeDispatcher {

    BridgeDispatcher NOOP = new BridgeDispatcher() {
        @Override
        public EnterResult onEnter(Class<?> declaringClass, java.lang.reflect.Method method,
                                   Object target, Object[] arguments) {
            return EnterResult.proceedWithoutContext();
        }

        @Override
        public ExitResult onExit(Object invocationToken, Object returnValue, Throwable throwable) {
            return ExitResult.proceed();
        }
    };

    EnterResult onEnter(
            Class<?> declaringClass,
            java.lang.reflect.Method method,
            Object target,
            Object[] arguments
    );

    ExitResult onExit(
            Object invocationToken,
            Object returnValue,
            Throwable throwable
    );
}
