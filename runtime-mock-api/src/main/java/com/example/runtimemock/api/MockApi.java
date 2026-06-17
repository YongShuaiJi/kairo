package com.example.runtimemock.api;

public interface MockApi {

    MockDecision proceed();

    MockDecision proceed(Object[] arguments);

    MockDecision returnValue(Object value);

    MockDecision returnJson(String json);

    MockDecision throwException(Throwable throwable);

    MockDecision throwException(String exceptionClassName, String message);

    Object newReturnObject();

    Object fromJson(String json, Class<?> targetType);

    Object get(Object target, String propertyPath);

    void set(Object target, String propertyPath, Object value);

    boolean isType(Object target, String className);
}
