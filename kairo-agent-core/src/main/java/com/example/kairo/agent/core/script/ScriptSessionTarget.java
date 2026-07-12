package com.example.kairo.agent.core.script;

import com.example.kairo.api.MethodSelector;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * A target method resolved for a {@code ScriptSession}, together with the stable identity used to
 * index the session for emergency deactivation.
 *
 * <p>{@code classId} follows the {@code LoadedClassRepository} base64url {@code loaderId|className}
 * convention so a deactivation request carrying either the class id or the bare class name can be
 * matched back to the session.
 */
public record ScriptSessionTarget(Method method, String classId, String className) {

    public ScriptSessionTarget {
        Objects.requireNonNull(method, "method");
        classId = requireText(classId, "classId");
        className = requireText(className, "className");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
