package com.example.kairo.core;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ClassLoaderIdentity {

    public static final String BOOTSTRAP = "bootstrap";

    private static final Object LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<ClassLoader, String> IDS = new WeakHashMap<>();

    private ClassLoaderIdentity() {
    }

    public static String idOf(ClassLoader classLoader) {
        if (classLoader == null) {
            return BOOTSTRAP;
        }
        synchronized (LOCK) {
            return IDS.computeIfAbsent(classLoader, ignored -> "loader-" + SEQUENCE.incrementAndGet());
        }
    }
}
