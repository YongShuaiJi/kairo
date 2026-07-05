package com.example.kairo.agent.core;

import java.util.Set;

public interface InstrumentationRegistry {

    boolean containsType(String className, ClassLoader classLoader);

    Set<MethodSignature> methodsOf(String className, ClassLoader classLoader);
}
