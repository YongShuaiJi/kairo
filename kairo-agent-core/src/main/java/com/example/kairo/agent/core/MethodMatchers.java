package com.example.kairo.agent.core;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Set;

/**
 * Byte Buddy {@link ElementMatcher} factories for the V1.3 unified target model.
 *
 * <p>Split out of the V1.2 single matcher so method and constructor locations can
 * be matched independently: method Advice attaches to non-constructor methods,
 * constructor Advice attaches to {@code <init>} methods. Call-site matching is
 * not an {@link ElementMatcher} &mdash; it is resolved by the call-site ASM
 * visitor against individual invoke instructions.
 */
final class MethodMatchers {

    private MethodMatchers() {
    }

    /** V1.2 compat: matches non-constructor methods in the set. */
    static ElementMatcher.Junction<MethodDescription> from(Set<MethodSignature> methods) {
        return methods(methods);
    }

    /** Matches non-constructor, non-abstract, non-native, non-bridge methods in the set. */
    static ElementMatcher.Junction<MethodDescription> methods(Set<MethodSignature> methods) {
        return new ElementMatcher.Junction.AbstractBase<>() {
            @Override
            public boolean matches(MethodDescription target) {
                if (!target.isMethod()
                        || target.isAbstract()
                        || target.isNative()
                        || target.isBridge()
                        || target.isTypeInitializer()
                        || target.isConstructor()) {
                    return false;
                }
                for (MethodSignature method : methods) {
                    if (target.getActualName().equals(method.methodName())
                            && target.getDescriptor().equals(method.methodDescriptor())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /** Matches constructors ({@code <init>}) in the set by descriptor. */
    static ElementMatcher.Junction<MethodDescription> constructors(Set<MethodSignature> constructors) {
        return new ElementMatcher.Junction.AbstractBase<>() {
            @Override
            public boolean matches(MethodDescription target) {
                if (!target.isConstructor() || target.isNative()) {
                    return false;
                }
                for (MethodSignature ctor : constructors) {
                    if (target.getDescriptor().equals(ctor.methodDescriptor())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
