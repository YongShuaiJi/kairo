package com.example.runtimemock.core;

import com.example.runtimemock.api.MethodMetadata;
import com.example.runtimemock.object.TypeConverter;

public final class DecisionValidator {

    public Object[] validateArguments(MethodMetadata method, Object[] arguments) {
        Class<?>[] parameterTypes = method.parameterTypes();
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        if (parameterTypes.length != arguments.length) {
            throw new IllegalArgumentException("Argument length mismatch, expected "
                    + parameterTypes.length + " but got " + arguments.length);
        }
        Object[] converted = arguments.clone();
        for (int i = 0; i < converted.length; i++) {
            if (!TypeConverter.isAssignable(parameterTypes[i], converted[i])) {
                throw new IllegalArgumentException("Argument " + i + " is not assignable to "
                        + parameterTypes[i].getName());
            }
            converted[i] = TypeConverter.convert(converted[i], parameterTypes[i]);
        }
        return converted;
    }

    public Object validateReturnValue(MethodMetadata method, Object returnValue) {
        Class<?> returnType = method.returnType();
        if (returnType == void.class) {
            if (returnValue != null) {
                throw new IllegalArgumentException("void method cannot return a value");
            }
            return null;
        }
        if (!TypeConverter.isAssignable(returnType, returnValue)) {
            throw new IllegalArgumentException("Return value is not assignable to " + returnType.getName());
        }
        return TypeConverter.convert(returnValue, returnType);
    }

    public Throwable validateThrowable(MethodMetadata method, Throwable throwable) {
        if (throwable == null) {
            throw new IllegalArgumentException("throwable must not be null");
        }
        if (throwable instanceof RuntimeException || throwable instanceof Error) {
            return throwable;
        }
        for (Class<?> exceptionType : method.exceptionTypes()) {
            if (exceptionType.isAssignableFrom(throwable.getClass())) {
                return throwable;
            }
        }
        throw new IllegalArgumentException("Checked exception " + throwable.getClass().getName()
                + " is not declared by " + method);
    }
}
