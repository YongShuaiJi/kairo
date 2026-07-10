package com.example.kairo.agent.core;

import java.util.Set;

/**
 * Registry of methods the agent currently needs to instrument. Lookups are
 * available both by {@code ClassLoader} (real transformation, where Byte Buddy
 * hands us the loader) and by opaque {@code classLoaderId} (read-only preview,
 * which has only the frozen {@code ClassIdentity}).
 */
public interface InstrumentationRegistry {

    boolean containsType(String className, ClassLoader classLoader);

    boolean containsType(String className, String classLoaderId);

    Set<MethodSignature> methodsOf(String className, ClassLoader classLoader);

    Set<MethodSignature> methodsOf(String className, String classLoaderId);
}
