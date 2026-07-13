package com.example.kairo.agent.core;

/**
 * Listener notified when a tracked ClassLoader has been garbage-collected
 * (V1.5 &sect;3.2). Implementations purge every cache whose key carries the
 * collected loader's stable id: bytecode snapshots, transformation journal,
 * script compilation cache, method cache and run statistics. The listener is
 * invoked on the cleanup thread and must not retain a strong reference to the
 * collected loader.
 */
@FunctionalInterface
public interface LoaderCleanupListener {

    /**
     * Called after the ClassLoader identified by {@code classLoaderId} was
     * observed to be garbage-collected. All residual entries keyed by that id
     * must be removed.
     */
    void onClassLoaderCollected(String classLoaderId);
}
