package com.example.kairo.api;

public interface MockApi {

    MockDecision proceed();

    MockDecision proceed(Object[] arguments);

    MockDecision returnValue(Object value);

    /**
     * V1.4: replace the current return value and continue the chain so later rules
     * observe it. Defaults to legacy {@link #returnValue(Object)} (terminate) for
     * implementations that predate V1.4.
     */
    default MockDecision replaceReturnValue(Object value) {
        return returnValue(value);
    }

    /**
     * V1.4: replace the current throwable and continue the chain. Defaults to legacy
     * {@link #throwException(Throwable)} (terminate).
     */
    default MockDecision replaceThrowable(Throwable throwable) {
        return throwException(throwable);
    }

    /**
     * V1.4: stop the enter-side chain and proceed to real execution. Defaults to
     * legacy {@link #proceed()}.
     */
    default MockDecision proceedOriginal() {
        return proceed();
    }

    /**
     * V1.4: discard this rule's modifications and continue with the prior outcome.
     * Defaults to legacy {@link #proceed()}.
     */
    default MockDecision failOpen() {
        return proceed();
    }

    MockDecision returnJson(String json);

    MockDecision throwException(Throwable throwable);

    MockDecision throwException(String exceptionClassName, String message);

    Object newReturnObject();

    Object fromJson(String json, Class<?> targetType);

    Object get(Object target, String propertyPath);

    void set(Object target, String propertyPath, Object value);

    boolean isType(Object target, String className);
}
