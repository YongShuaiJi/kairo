package com.example.kairo.agent.core;

import java.util.List;

/**
 * Discovery metadata for one enhanceable member of a loaded class.
 *
 * <p>V1.3 unifies methods and constructors under this record: a constructor
 * carries {@code name = "<init>"}, {@code memberKind = "CONSTRUCTOR"} and a
 * {@code returnType} equal to the declaring class. The modifier-derived flags
 * let the platform mark special members (native / abstract / final /
 * synchronized / synthetic / bridge) so the Web can refuse or warn before a
 * rule is saved against a member V1.3 cannot enhance.
 */
public record MethodInfo(
        String name,
        String descriptor,
        String returnType,
        List<String> parameterTypes,
        List<String> exceptionTypes,
        int modifiers,
        boolean isStatic,
        boolean isPrivate,
        String memberKind,
        boolean nativeMethod,
        boolean abstractMethod,
        boolean finalMethod,
        boolean synchronizedMethod,
        boolean synthetic,
        boolean bridge
) {

    /** Whether this member is a constructor ({@code <init>}). */
    public boolean isConstructor() {
        return "CONSTRUCTOR".equals(memberKind);
    }
}
