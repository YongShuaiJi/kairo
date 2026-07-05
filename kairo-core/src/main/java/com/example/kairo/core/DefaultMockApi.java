package com.example.kairo.core;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MockApi;
import com.example.kairo.api.MockDecision;
import com.example.kairo.object.RuntimeObjectFactory;

import java.util.Objects;

public final class DefaultMockApi implements MockApi {

    private final InvocationContext context;
    private final RuntimeObjectFactory objectFactory;

    public DefaultMockApi(InvocationContext context, RuntimeObjectFactory objectFactory) {
        this.context = Objects.requireNonNull(context, "context");
        this.objectFactory = Objects.requireNonNull(objectFactory, "objectFactory");
    }

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
        Object value = objectFactory.fromJson(
                json,
                context.method().returnType(),
                context.method().targetClassLoader()
        );
        return MockDecision.returnValue(value);
    }

    @Override
    public MockDecision throwException(Throwable throwable) {
        return MockDecision.throwException(throwable);
    }

    @Override
    public MockDecision throwException(String exceptionClassName, String message) {
        return MockDecision.throwException(objectFactory.newThrowable(
                exceptionClassName,
                message,
                context.method().targetClassLoader()
        ));
    }

    @Override
    public Object newReturnObject() {
        return objectFactory.newInstance(context.method().returnType());
    }

    @Override
    public Object fromJson(String json, Class<?> targetType) {
        return objectFactory.fromJson(json, targetType, context.method().targetClassLoader());
    }

    @Override
    public Object get(Object target, String propertyPath) {
        return objectFactory.getProperty(target, propertyPath);
    }

    @Override
    public void set(Object target, String propertyPath, Object value) {
        objectFactory.setProperty(target, propertyPath, value);
    }

    @Override
    public boolean isType(Object target, String className) {
        if (target == null) {
            return false;
        }
        try {
            Class<?> type = Class.forName(className, false, context.method().targetClassLoader());
            return type.isInstance(target);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
