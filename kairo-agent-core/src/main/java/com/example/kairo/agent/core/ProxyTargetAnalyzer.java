package com.example.kairo.agent.core;

import com.example.kairo.api.ProxyAnalysis;

import java.lang.reflect.Method;

/**
 * SPI that analyzes a loaded class for proxy structure (V1.5 &sect;4.2).
 *
 * <p>The analyzer classifies a class as a JDK dynamic proxy, CGLIB subclass
 * proxy, Byte Buddy proxy, plain class or unknown generated class, and produces
 * a {@link ProxyAnalysis} describing the proxy interfaces or superclass, the
 * candidate user methods, a recommended enhancement target and the impact of
 * enhancing the proxy class versus the target class versus both.
 *
 * <p>Implementations must be advisory only: the system never auto-jumps from a
 * proxy class to its target and publishes on its own (&sect;4.2). The caller
 * makes the final selection and records it in the rule target and audit.
 */
public interface ProxyTargetAnalyzer {

    /** Analyze {@code type} and return the proxy structure / recommendation. */
    ProxyAnalysis analyze(Class<?> type);

    /**
     * Convenience: the {@link ProxyType} of a method's declaring class, used by
     * the publish path to stamp a resolved target with its proxy classification.
     */
    default com.example.kairo.api.ProxyType proxyTypeOf(Method method) {
        if (method == null) {
            return com.example.kairo.api.ProxyType.PLAIN;
        }
        return analyze(method.getDeclaringClass()).proxyType();
    }
}
