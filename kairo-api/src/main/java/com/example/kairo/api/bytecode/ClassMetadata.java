package com.example.kairo.api.bytecode;

import com.example.kairo.api.SupportLevel;

import java.util.Objects;

/**
 * Enrichment metadata for a loaded class, attached <em>alongside</em> the
 * {@link ClassIdentity} pair (V1.5 &sect;3.1).
 *
 * <p>V1.1 froze {@link ClassIdentity} as the stable equality key
 * {@code (binaryClassName, classLoaderId)} and noted that module, code source
 * and bytecode hash would be "optional enrichment attached alongside the
 * identity rather than folded into equality". V1.5 attaches that enrichment
 * here, on a companion type, so the identity pair remains the stable map key
 * used by every registry, snapshot and script cache while the discovery and
 * diagnostics paths can describe a class fully.
 *
 * <p>Every field except {@link #identity()} is nullable: a value is present only
 * when the agent actually observed it. {@code bytecodeHash} is the hash of the
 * bytes currently running in the JVM at observation time and may go stale after a
 * hot update; callers that need a fresh value re-derive it. {@code supportLevel}
 * is the V1.5 &sect;2 declared level for enhancing this class.
 *
 * <p>{@code classLoaderId} only guarantees uniqueness within a single JVM
 * lifetime; across restarts a rule is re-resolved by application, class name,
 * loader selector and code source, never by assuming an old id is still valid.
 */
public final class ClassMetadata {

    private final ClassIdentity identity;
    private final String loaderClassName;
    private final String parentLoaderId;
    private final String moduleName;
    private final boolean namedModule;
    private final String codeSource;
    private final String protectionDomainSummary;
    private final String bytecodeHash;
    private final SupportLevel supportLevel;

    public ClassMetadata(ClassIdentity identity, String loaderClassName, String parentLoaderId,
                         String moduleName, boolean namedModule, String codeSource,
                         String protectionDomainSummary, String bytecodeHash,
                         SupportLevel supportLevel) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.loaderClassName = loaderClassName;
        this.parentLoaderId = parentLoaderId;
        this.moduleName = moduleName;
        this.namedModule = namedModule;
        this.codeSource = codeSource;
        this.protectionDomainSummary = protectionDomainSummary;
        this.bytecodeHash = bytecodeHash;
        this.supportLevel = supportLevel == null ? SupportLevel.SUPPORTED : supportLevel;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The stable identity pair this metadata describes. */
    public ClassIdentity identity() {
        return identity;
    }

    /** {@link ClassLoader#getClass()} name of the defining loader, or {@code "bootstrap"}. */
    public String loaderClassName() {
        return loaderClassName;
    }

    /** Stable id of the parent loader, or {@code "bootstrap"} for the bootstrap loader's child. */
    public String parentLoaderId() {
        return parentLoaderId;
    }

    /** Module name when the class is in a named module, else {@code null}. */
    public String moduleName() {
        return moduleName;
    }

    /** Whether the class is in a named (not unnamed) module. */
    public boolean namedModule() {
        return namedModule;
    }

    /** Code source URL string, or {@code null} when unknown (e.g. bootstrap classes). */
    public String codeSource() {
        return codeSource;
    }

    /** Short summary/hash of the protection domain, or {@code null}. */
    public String protectionDomainSummary() {
        return protectionDomainSummary;
    }

    /** SHA-256 of the bytes running in the JVM at observation time; may be stale after a hot update. */
    public String bytecodeHash() {
        return bytecodeHash;
    }

    /** Declared V1.5 support level for enhancing this class. */
    public SupportLevel supportLevel() {
        return supportLevel;
    }

    public Builder toBuilder() {
        return builder()
                .identity(identity)
                .loaderClassName(loaderClassName)
                .parentLoaderId(parentLoaderId)
                .moduleName(moduleName)
                .namedModule(namedModule)
                .codeSource(codeSource)
                .protectionDomainSummary(protectionDomainSummary)
                .bytecodeHash(bytecodeHash)
                .supportLevel(supportLevel);
    }

    public static final class Builder {
        private ClassIdentity identity;
        private String loaderClassName;
        private String parentLoaderId;
        private String moduleName;
        private boolean namedModule;
        private String codeSource;
        private String protectionDomainSummary;
        private String bytecodeHash;
        private SupportLevel supportLevel;

        private Builder() {
        }

        public Builder identity(ClassIdentity identity) {
            this.identity = identity;
            return this;
        }

        public Builder loaderClassName(String loaderClassName) {
            this.loaderClassName = loaderClassName;
            return this;
        }

        public Builder parentLoaderId(String parentLoaderId) {
            this.parentLoaderId = parentLoaderId;
            return this;
        }

        public Builder moduleName(String moduleName) {
            this.moduleName = moduleName;
            return this;
        }

        public Builder namedModule(boolean namedModule) {
            this.namedModule = namedModule;
            return this;
        }

        public Builder codeSource(String codeSource) {
            this.codeSource = codeSource;
            return this;
        }

        public Builder protectionDomainSummary(String protectionDomainSummary) {
            this.protectionDomainSummary = protectionDomainSummary;
            return this;
        }

        public Builder bytecodeHash(String bytecodeHash) {
            this.bytecodeHash = bytecodeHash;
            return this;
        }

        public Builder supportLevel(SupportLevel supportLevel) {
            this.supportLevel = supportLevel;
            return this;
        }

        public ClassMetadata build() {
            return new ClassMetadata(identity, loaderClassName, parentLoaderId, moduleName,
                    namedModule, codeSource, protectionDomainSummary, bytecodeHash, supportLevel);
        }
    }
}
