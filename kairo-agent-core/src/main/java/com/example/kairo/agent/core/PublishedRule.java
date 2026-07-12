package com.example.kairo.agent.core;

import com.example.kairo.api.MockRule;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.MethodKey;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * A rule currently published on the agent. Either {@code method} or
 * {@code constructor} is set: method-phase and call-site rules carry the
 * reflective {@code Method} (the caller for call-site rules); constructor rules
 * carry the reflective {@code Constructor} and a {@code null} method.
 */
record PublishedRule(Method method, Constructor<?> constructor, MethodKey methodKey,
                     MockRule rule, CompiledRule compiledRule) {

    /** The declaring class of the enhanced member, regardless of kind. */
    Class<?> declaringClass() {
        return method != null ? method.getDeclaringClass()
                : (constructor != null ? constructor.getDeclaringClass() : methodKey.declaringClass());
    }
}
