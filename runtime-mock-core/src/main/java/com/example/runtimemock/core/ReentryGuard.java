package com.example.runtimemock.core;

import java.util.HashSet;
import java.util.Set;

public final class ReentryGuard {

    private final ThreadLocal<Set<String>> active = ThreadLocal.withInitial(HashSet::new);

    public Scope enter(MethodKey methodKey, String ruleId) {
        String key = methodKey + "::" + ruleId;
        Set<String> activeKeys = active.get();
        if (activeKeys.contains(key)) {
            return new Scope(false, key);
        }
        activeKeys.add(key);
        return new Scope(true, key);
    }

    public final class Scope implements AutoCloseable {
        private final boolean entered;
        private final String key;
        private boolean closed;

        private Scope(boolean entered, String key) {
            this.entered = entered;
            this.key = key;
        }

        public boolean entered() {
            return entered;
        }

        @Override
        public void close() {
            if (!entered || closed) {
                return;
            }
            Set<String> activeKeys = active.get();
            activeKeys.remove(key);
            if (activeKeys.isEmpty()) {
                active.remove();
            }
            closed = true;
        }
    }
}
