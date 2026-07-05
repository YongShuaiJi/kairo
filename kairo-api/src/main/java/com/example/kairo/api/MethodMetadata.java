package com.example.kairo.api;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

public final class MethodMetadata {

    private final Class<?> declaringClass;
    private final String name;
    private final String descriptor;
    private final Class<?> returnType;
    private final Class<?>[] parameterTypes;
    private final Class<?>[] exceptionTypes;
    private final Method reflectMethod;

    public MethodMetadata(Method reflectMethod, String descriptor) {
        this.reflectMethod = Objects.requireNonNull(reflectMethod, "reflectMethod");
        this.declaringClass = reflectMethod.getDeclaringClass();
        this.name = reflectMethod.getName();
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.returnType = reflectMethod.getReturnType();
        this.parameterTypes = reflectMethod.getParameterTypes().clone();
        this.exceptionTypes = reflectMethod.getExceptionTypes().clone();
    }

    public Class<?> declaringClass() {
        return declaringClass;
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    public Class<?> returnType() {
        return returnType;
    }

    public Class<?>[] parameterTypes() {
        return parameterTypes.clone();
    }

    public Class<?>[] exceptionTypes() {
        return exceptionTypes.clone();
    }

    public Method reflectMethod() {
        return reflectMethod;
    }

    public ClassLoader targetClassLoader() {
        return declaringClass.getClassLoader();
    }

    @Override
    public String toString() {
        return declaringClass.getName() + "#" + name + descriptor
                + " throws " + Arrays.toString(exceptionTypes);
    }
}
