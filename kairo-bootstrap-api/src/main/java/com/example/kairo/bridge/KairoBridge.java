package com.example.kairo.bridge;

public final class KairoBridge {

    private static volatile BridgeDispatcher dispatcher = BridgeDispatcher.NOOP;

    private KairoBridge() {
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

    /**
     * V1.3 entry point for constructor and call-site enhancement locations.
     * Fail-open: any dispatcher failure yields a no-context proceed so the
     * original construct runs unchanged.
     */
    public static EnterResult enterV2(InvocationEnvelope envelope) {
        if (envelope == null) {
            return EnterResult.proceedWithoutContext();
        }
        try {
            return dispatcher.onEnterV2(envelope);
        } catch (Throwable ignored) {
            return EnterResult.proceedWithoutContext();
        }
    }

    /**
     * V1.3 exit for constructor and call-site enhancement locations.
     */
    public static ExitResult exitV2(Object invocationToken, OutcomeEnvelope outcome) {
        if (invocationToken == null) {
            return ExitResult.proceed();
        }
        if (outcome == null) {
            outcome = OutcomeEnvelope.ofReturn(null);
        }
        try {
            return dispatcher.onExitV2(invocationToken, outcome);
        } catch (Throwable ignored) {
            return ExitResult.proceed();
        }
    }
}
