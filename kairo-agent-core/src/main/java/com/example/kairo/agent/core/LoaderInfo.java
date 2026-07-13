package com.example.kairo.agent.core;

/**
 * Snapshot of a tracked ClassLoader (V1.5 &sect;3.1 / &sect;4.1).
 *
 * <p>Carries the stable loader id, the loader's own class name, its parent's
 * stable id and an optional code source, so the platform can render a loader
 * tree and so a cross-restart rule can re-resolve without assuming an old id is
 * still valid. The bootstrap loader is represented by the canonical id
 * {@link com.example.kairo.core.ClassLoaderIdentity#BOOTSTRAP} with class name
 * {@code "bootstrap"}.
 */
public record LoaderInfo(
        String id,
        String className,
        String parentId,
        String codeSource
) {
}
