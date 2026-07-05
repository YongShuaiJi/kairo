package com.example.kairo.agent.core;

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
