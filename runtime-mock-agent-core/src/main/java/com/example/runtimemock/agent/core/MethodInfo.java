package com.example.runtimemock.agent.core;

import java.util.List;

public record MethodInfo(
        String name,
        String descriptor,
        String returnType,
        List<String> parameterTypes,
        List<String> exceptionTypes,
        int modifiers,
        boolean isStatic,
        boolean isPrivate
) {
}
