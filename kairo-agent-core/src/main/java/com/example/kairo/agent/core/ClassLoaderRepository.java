package com.example.kairo.agent.core;

import com.example.kairo.core.ClassLoaderIdentity;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifecycle-aware registry of the ClassLoaders the agent has observed
 * (V1.5 &sect;3.2 / &sect;4.1).
 *
 * <p>The identity algorithm in {@link ClassLoaderIdentity} already uses a
 * {@link java.util.WeakHashMap} so a loader can be garbage-collected while no
 * strong references remain. This repository adds the reverse direction that
 * V1.5 requires: a stable-id &rarr; weak-loader map, a parent/child tree and a
 * {@link ReferenceQueue} cleaner that fires {@link LoaderCleanupListener}s the
 * moment a tracked loader is collected, so residual caches keyed by loader id
 * (bytecode snapshots, transformation journal, script compile cache, method
 * cache, run statistics) are synchronously purged instead of leaking for the
 * agent's lifetime.
 *
 * <p>Loaders are registered as the agent observes them (class search, target
 * resolution, transformation). The bootstrap loader is represented by
 * {@link ClassLoaderIdentity#BOOTSTRAP}; it is never tracked weakly because it
 * is never collected.
 */
public final class ClassLoaderRepository {

    private final ReferenceQueue<ClassLoader> queue = new ReferenceQueue<>();
    private final ConcurrentHashMap<String, LoaderReference> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LoaderInfo> infoById = new ConcurrentHashMap<>();
    private final List<LoaderCleanupListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Register a loader and return its stable id. Idempotent: a loader already
     * tracked keeps its id. Records the loader's class name, parent id and code
     * source so the platform can render the loader tree and re-resolve a rule
     * across restarts. {@code null} registers the bootstrap loader.
     */
    public String register(ClassLoader loader) {
        if (loader == null) {
            infoById.computeIfAbsent(ClassLoaderIdentity.BOOTSTRAP,
                    id -> new LoaderInfo(id, "bootstrap", null, null));
            return ClassLoaderIdentity.BOOTSTRAP;
        }
        String id = ClassLoaderIdentity.idOf(loader);
        byId.computeIfAbsent(id, ignored -> new LoaderReference(loader, id, queue));
        infoById.computeIfAbsent(id, ignored -> infoOf(loader, id));
        return id;
    }

    /** Convenience: register the loader that defined {@code type}. */
    public String register(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return register(type.getClassLoader());
    }

    /** The live loader for an id, or empty when the loader was collected or never tracked. */
    public Optional<ClassLoader> findLoader(String classLoaderId) {
        if (classLoaderId == null || ClassLoaderIdentity.BOOTSTRAP.equals(classLoaderId)) {
            return Optional.empty();
        }
        LoaderReference ref = byId.get(classLoaderId);
        return ref == null ? Optional.empty() : Optional.ofNullable(ref.get());
    }

    /** The recorded info for an id, or empty when the loader was never tracked. */
    public Optional<LoaderInfo> loaderInfo(String classLoaderId) {
        return Optional.ofNullable(classLoaderId == null ? null : infoById.get(classLoaderId));
    }

    /** All currently-tracked loaders (bootstrap first, then by id) for the loader-tree API. */
    public List<LoaderInfo> liveLoaders() {
        List<LoaderInfo> all = new ArrayList<>(infoById.size());
        LoaderInfo bootstrap = infoById.get(ClassLoaderIdentity.BOOTSTRAP);
        if (bootstrap != null) {
            all.add(bootstrap);
        }
        infoById.forEach((id, info) -> {
            if (!ClassLoaderIdentity.BOOTSTRAP.equals(id) && byId.get(id) != null && byId.get(id).get() != null) {
                all.add(info);
            }
        });
        return all;
    }

    /**
     * A parent &rarr; children tree derived from {@link #liveLoaders()}, keyed by
     * parent id. Bootstrap children key under {@link ClassLoaderIdentity#BOOTSTRAP}.
     */
    public Map<String, List<LoaderInfo>> loaderTree() {
        Map<String, List<LoaderInfo>> tree = new LinkedHashMap<>();
        for (LoaderInfo info : liveLoaders()) {
            String parent = info.parentId() == null ? ClassLoaderIdentity.BOOTSTRAP : info.parentId();
            tree.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(info);
        }
        return tree;
    }

    public void addListener(LoaderCleanupListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public boolean removeListener(LoaderCleanupListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Drain the {@link ReferenceQueue}: for every collected loader, remove its
     * tracking entries and fire the cleanup listeners with its id. Returns the
     * number of loaders collected this pass. Called periodically by the agent
     * cleanup executor; safe to call concurrently.
     */
    public int pollCollected() {
        int collected = 0;
        LoaderReference ref;
        while ((ref = (LoaderReference) queue.poll()) != null) {
            String id = ref.loaderId;
            byId.remove(id, ref);
            infoById.remove(id);
            for (LoaderCleanupListener listener : listeners) {
                try {
                    listener.onClassLoaderCollected(id);
                } catch (RuntimeException ignored) {
                    // a cleanup listener must never break the cleaner
                }
            }
            collected++;
        }
        return collected;
    }

    /** Number of currently-tracked (non-bootstrap) loaders. */
    public int trackedLoaderCount() {
        return byId.size();
    }

    /** Remove all tracking; used by tests and agent close. */
    public void clear() {
        byId.clear();
        infoById.clear();
        // drain the queue without firing listeners
        while (queue.poll() != null) {
            // discard
        }
    }

    private static LoaderInfo infoOf(ClassLoader loader, String id) {
        String className = loader.getClass().getName();
        String parentId = ClassLoaderIdentity.idOf(loader.getParent());
        String codeSource = codeSourceOf(loader);
        return new LoaderInfo(id, className, parentId, codeSource);
    }

    private static String codeSourceOf(ClassLoader loader) {
        try {
            java.security.ProtectionDomain pd = loader.getClass().getProtectionDomain();
            if (pd == null || pd.getCodeSource() == null || pd.getCodeSource().getLocation() == null) {
                return null;
            }
            return pd.getCodeSource().getLocation().toString();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    /** Weak reference that remembers its loader id so the cleaner can purge by id. */
    private static final class LoaderReference extends WeakReference<ClassLoader> {
        final String loaderId;

        LoaderReference(ClassLoader referent, String loaderId, ReferenceQueue<ClassLoader> queue) {
            super(referent, queue);
            this.loaderId = loaderId;
        }
    }
}
