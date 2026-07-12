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

        @Override
        public EnterResult onEnterV2(InvocationEnvelope envelope) {
            return EnterResult.proceedWithoutContext();
        }

        @Override
        public ExitResult onExitV2(Object invocationToken, OutcomeEnvelope outcome) {
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

    /**
     * V1.3 entry point for constructor and call-site locations that the V1
     * {@link #onEnter(Class, java.lang.reflect.Method, Object, Object[])}
     * signature cannot express. Default implementation forwards to the V1
     * no-context proceed result so old dispatcher implementations (rolling
     * upgrade) remain safe.
     */
    default EnterResult onEnterV2(InvocationEnvelope envelope) {
        return EnterResult.proceedWithoutContext();
    }

    /**
     * V1.3 exit for constructor and call-site locations. Default forwards to
     * V1 {@link #onExit(Object, Object, Throwable)} semantics (proceed).
     */
    default ExitResult onExitV2(Object invocationToken, OutcomeEnvelope outcome) {
        return ExitResult.proceed();
    }
}
