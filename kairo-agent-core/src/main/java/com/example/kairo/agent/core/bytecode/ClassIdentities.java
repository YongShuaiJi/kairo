package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.core.ClassLoaderIdentity;

import java.util.Objects;

/**
 * Bridge from {@code kairo-core}'s {@link ClassLoaderIdentity} algorithm to the
 * frozen {@link ClassIdentity} DTO. All agent-side code that needs a
 * {@code ClassIdentity} for a live {@code Class} must go through here so the
 * {@code classLoaderId} is always produced by the single existing identity
 * algorithm rather than a competing one.
 */
public final class ClassIdentities {

    private ClassIdentities() {
    }

    public static ClassIdentity of(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return new ClassIdentity(type.getName(), ClassLoaderIdentity.idOf(type.getClassLoader()));
    }

    public static ClassIdentity of(String binaryClassName, ClassLoader classLoader) {
        if (binaryClassName == null || binaryClassName.isBlank()) {
            throw new IllegalArgumentException("binaryClassName must not be blank");
        }
        return new ClassIdentity(binaryClassName, ClassLoaderIdentity.idOf(classLoader));
    }
}
