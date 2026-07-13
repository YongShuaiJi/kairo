package com.example.kairo.api;

import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.ClassMetadata;

import java.util.List;
import java.util.Objects;

/**
 * Instance-level target produced when a rule is resolved against a live JVM
 * (V1.5 &sect;5: "实际发布产生实例级 ResolvedTarget").
 *
 * <p>A {@link ClassSelector} names a class that may not be loaded yet; an
 * {@link EnhancementTarget} names a resolved method. Neither carries the full
 * V1.5 enrichment (support level, proxy type, observed metadata) the platform
 * must persist and audit. {@code ResolvedTarget} is that envelope: it binds the
 * authoritative {@link EnhancementTarget} to the concrete {@link ClassIdentity}
 * the agent observed, the declared {@link SupportLevel}, the detected
 * {@link ProxyType} and any diagnostic notes, so the platform can record exactly
 * which class was enhanced and at what support level.
 */
public final class ResolvedTarget {

    private final EnhancementTarget target;
    private final ClassIdentity identity;
    private final ClassMetadata metadata;
    private final SupportLevel supportLevel;
    private final ProxyType proxyType;
    private final List<String> notes;
    private final long resolvedAtMillis;

    public ResolvedTarget(EnhancementTarget target, ClassIdentity identity, ClassMetadata metadata,
                          SupportLevel supportLevel, ProxyType proxyType, List<String> notes,
                          long resolvedAtMillis) {
        this.target = Objects.requireNonNull(target, "target");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.metadata = metadata;
        this.supportLevel = supportLevel == null ? SupportLevel.SUPPORTED : supportLevel;
        this.proxyType = proxyType == null ? ProxyType.PLAIN : proxyType;
        this.notes = notes == null ? List.of() : List.copyOf(notes);
        this.resolvedAtMillis = resolvedAtMillis;
    }

    public EnhancementTarget target() {
        return target;
    }

    public ClassIdentity identity() {
        return identity;
    }

    public ClassMetadata metadata() {
        return metadata;
    }

    public SupportLevel supportLevel() {
        return supportLevel;
    }

    public ProxyType proxyType() {
        return proxyType;
    }

    public List<String> notes() {
        return notes;
    }

    public long resolvedAtMillis() {
        return resolvedAtMillis;
    }
}
