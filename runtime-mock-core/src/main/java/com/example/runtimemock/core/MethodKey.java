package com.example.runtimemock.core;

import java.lang.reflect.Method;
import java.util.Objects;

public final class MethodKey {

    private final Class<?> declaringClass;
    private final String methodName;
    private final String methodDescriptor;

    public MethodKey(Class<?> declaringClass, String methodName, String methodDescriptor) {
        this.declaringClass = Objects.requireNonNull(declaringClass, "declaringClass");
        this.methodName = Objects.requireNonNull(methodName, "methodName");
        this.methodDescriptor = Objects.requireNonNull(methodDescriptor, "methodDescriptor");
    }

    public static MethodKey of(Method method) {
        return new MethodKey(method.getDeclaringClass(), method.getName(), MethodDescriptor.of(method));
    }

    public Class<?> declaringClass() {
        return declaringClass;
    }

    public String className() {
        return declaringClass.getName();
    }

    public String classLoaderId() {
        return ClassLoaderIdentity.idOf(declaringClass.getClassLoader());
    }

    public String methodName() {
        return methodName;
    }

    public String methodDescriptor() {
        return methodDescriptor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodKey methodKey)) {
            return false;
        }
        return declaringClass == methodKey.declaringClass
                && methodName.equals(methodKey.methodName)
                && methodDescriptor.equals(methodKey.methodDescriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(declaringClass), methodName, methodDescriptor);
    }

    @Override
    public String toString() {
        return declaringClass.getName() + "#" + methodName + methodDescriptor
                + "@" + classLoaderId();
    }
}
