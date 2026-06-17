package com.example.runtimemock.core;

import java.lang.reflect.Method;

public final class MethodDescriptor {

    private MethodDescriptor() {
    }

    public static String of(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameterType : method.getParameterTypes()) {
            descriptor.append(of(parameterType));
        }
        descriptor.append(')').append(of(method.getReturnType()));
        return descriptor.toString();
    }

    public static String of(Class<?> type) {
        if (type == void.class) {
            return "V";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type == byte.class) {
            return "B";
        }
        if (type == char.class) {
            return "C";
        }
        if (type == short.class) {
            return "S";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == float.class) {
            return "F";
        }
        if (type == double.class) {
            return "D";
        }
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
