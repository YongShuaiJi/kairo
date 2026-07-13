package com.example.kairo.api;

/**
 * Kind of class the agent is looking at, for proxy-target analysis (V1.5 &sect;4.2).
 *
 * <p>The {@link com.example.kairo.api.bytecode.ClassIdentity} pair names a class
 * unambiguously, but a class may itself be a generated proxy whose bytecode does
 * not describe the user's intent. This enum is the coarse classification
 * {@code ProxyTargetAnalyzer} produces so the platform can present the declared
 * class, the proxy class and the recommended enhancement target separately and
 * refuse to silently jump from one to another.
 */
public enum ProxyType {

    /** A plain user class; no proxy machinery detected. */
    PLAIN,
    /** A {@link java.lang.reflect.Proxy} / JDK dynamic proxy backed by interfaces. */
    JDK_PROXY,
    /** A CGLIB-generated subclass proxy. */
    CGLIB,
    /** A Byte Buddy-generated proxy or subclass. */
    BYTE_BUDDY,
    /** A generated/hidden class the analyzer could not classify (incl. some lambda forms). */
    UNKNOWN
}
