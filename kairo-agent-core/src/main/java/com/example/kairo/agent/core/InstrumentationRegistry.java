package com.example.kairo.agent.core;

import com.example.kairo.api.EnhancementTarget;

import java.util.Set;

/**
 * Registry of enhancement targets the agent currently needs to instrument.
 *
 * <p>V1.3 upgrades the V1.2 method-set registry to a target/location set: each
 * entry is an {@link EnhancementTarget} (method or constructor identity +
 * {@link com.example.kairo.api.EnhancementLocation} + optional call-site
 * selector). Lookups are available both by {@code ClassLoader} (real
 * transformation) and by opaque {@code classLoaderId} (read-only preview).
 *
 * <p>The registry is the source of truth for what a class needs woven: method
 * Advice, constructor Advice and call-site visitors are all derived from the
 * target set returned by {@link #targetsOf}.
 */
public interface InstrumentationRegistry {

    boolean containsType(String className, ClassLoader classLoader);

    boolean containsType(String className, String classLoaderId);

    /** All enhancement targets registered for a class, filtered by class-loader id. */
    Set<EnhancementTarget> targetsOf(String className, ClassLoader classLoader);

    Set<EnhancementTarget> targetsOf(String className, String classLoaderId);

    /** V1.2 compat: the distinct method signatures that carry at least one target. */
    Set<MethodSignature> methodsOf(String className, ClassLoader classLoader);

    Set<MethodSignature> methodsOf(String className, String classLoaderId);
}
