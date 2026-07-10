package com.example.kairo.api.bytecode;

import java.util.Objects;

/**
 * Frozen identity of a loaded class inside a target JVM.
 *
 * <p>Identity is the pair {@code (binaryClassName, classLoaderId)}. The
 * {@code classLoaderId} is the stable identifier produced by
 * {@code com.example.kairo.core.ClassLoaderIdentity#idOf(ClassLoader)} in
 * {@code kairo-core}; this class never recomputes it, so there is a single
 * class-loader identity algorithm across the whole system. Callers in
 * {@code kairo-agent-core} build instances via {@code ClassIdentities.of(...)}
 * which delegates to that algorithm.
 *
 * <p>This type is intentionally minimal. Module, code source and bytecode hash
 * are optional enrichment that later V1.1 slices attach alongside the identity
 * rather than folding into equality, so the identity pair stays the stable key.
 */
public final class ClassIdentity {

    private final String binaryClassName;
    private final String classLoaderId;

    public ClassIdentity(String binaryClassName, String classLoaderId) {
        this.binaryClassName = requireText(binaryClassName, "binaryClassName");
        this.classLoaderId = requireText(classLoaderId, "classLoaderId");
    }

    public static ClassIdentity of(String binaryClassName, String classLoaderId) {
        return new ClassIdentity(binaryClassName, classLoaderId);
    }

    public String binaryClassName() {
        return binaryClassName;
    }

    public String classLoaderId() {
        return classLoaderId;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClassIdentity that)) {
            return false;
        }
        return binaryClassName.equals(that.binaryClassName)
                && classLoaderId.equals(that.classLoaderId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(binaryClassName, classLoaderId);
    }

    @Override
    public String toString() {
        return binaryClassName + "@" + classLoaderId;
    }
}
