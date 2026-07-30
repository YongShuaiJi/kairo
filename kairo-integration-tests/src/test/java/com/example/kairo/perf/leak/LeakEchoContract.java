package com.example.kairo.perf.leak;

/**
 * Stable parent-loader view used to invoke unloadable leak fixtures without
 * {@link java.lang.reflect.Method#invoke(Object, Object...)} creating per-loader
 * hidden-method-handle classes in the harness JVM.
 */
public interface LeakEchoContract {

    String echo(String value);
}
