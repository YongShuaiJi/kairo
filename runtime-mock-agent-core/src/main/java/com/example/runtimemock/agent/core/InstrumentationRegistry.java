package com.example.runtimemock.agent.core;

import java.util.Set;

public interface InstrumentationRegistry {

    boolean containsType(String className, ClassLoader classLoader);

    Set<MethodSignature> methodsOf(String className, ClassLoader classLoader);
}
