package com.example.kairo.core;

import com.example.kairo.api.EnhancementLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-thread reentry guard keyed by method + enhancement location + rule id.
 *
 * <p>V1.3 folds the {@link EnhancementLocation} into the key so that a call-site
 * rule and a method rule attached to the same method cannot be confused for
 * reentry of one another &mdash; a call-site rule firing inside a method that
 * also carries a method-phase rule must not be suppressed, and vice versa. The
 * rule id alone already distinguishes rules; the location makes the reentry
 * scope explicit and per-target as the V1.3 plan requires.
 */
public final class ReentryGuard {

    private final ThreadLocal<Set<String>> active = ThreadLocal.withInitial(HashSet::new);

    public Scope enter(MethodKey methodKey, String ruleId) {
        return enter(methodKey, EnhancementLocation.METHOD_ENTER, ruleId);
    }

    public Scope enter(MethodKey methodKey, EnhancementLocation location, String ruleId) {
        String key = methodKey + "@" + (location == null ? EnhancementLocation.METHOD_ENTER : location)
                + "::" + ruleId;
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
