package com.example.kairo.groovy;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stable, process-wide identifier for a {@link ClassLoader} instance.
 *
 * <p>This mirrors {@code com.example.kairo.core.ClassLoaderIdentity.idOf} (same algorithm:
 * a weak map backed by a monotonic sequence, {@code "bootstrap"} for the null loader).
 * {@code kairo-groovy} cannot depend on {@code kairo-core} (the dependency points the other
 * way: {@code kairo-core} depends on {@code kairo-groovy}), so the canonical mapper is
 * reproduced locally rather than reused. Callers that already hold a canonical id from the
 * agent layer may pass it explicitly to {@link ScriptCompilationContext} to stay consistent
 * across modules.
 */
final class TargetClassLoaderIds {

    static final String BOOTSTRAP = "bootstrap";

    private static final Object LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<ClassLoader, String> IDS = new WeakHashMap<>();

    private TargetClassLoaderIds() {
    }

    static String idOf(ClassLoader classLoader) {
        if (classLoader == null) {
            return BOOTSTRAP;
        }
        synchronized (LOCK) {
            return IDS.computeIfAbsent(classLoader, ignored -> "loader-" + SEQUENCE.incrementAndGet());
        }
    }
}
