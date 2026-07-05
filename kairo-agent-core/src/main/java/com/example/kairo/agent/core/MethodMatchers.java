package com.example.kairo.agent.core;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Set;

final class MethodMatchers {

    private MethodMatchers() {
    }

    static ElementMatcher.Junction<MethodDescription> from(Set<MethodSignature> methods) {
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
}
