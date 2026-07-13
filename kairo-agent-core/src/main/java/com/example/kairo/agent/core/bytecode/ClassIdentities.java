package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.SupportLevel;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.ClassMetadata;
import com.example.kairo.core.ClassLoaderIdentity;

import java.lang.module.ModuleDescriptor;
import java.security.ProtectionDomain;
import java.util.Objects;

/**
 * Bridge from {@code kairo-core}'s {@link ClassLoaderIdentity} algorithm to the
 * frozen {@link ClassIdentity} DTO. All agent-side code that needs a
 * {@code ClassIdentity} for a live {@code Class} must go through here so the
 * {@code classLoaderId} is always produced by the single existing identity
 * algorithm rather than a competing one.
 *
 * <p>V1.5 adds {@link #metadataOf(Class, SupportLevel)} which derives the full
 * {@link ClassMetadata} enrichment (loader class name, parent loader id, module
 * / named state, code source, protection-domain summary) attached alongside the
 * identity pair. The bytecode hash is left {@code null} here and stamped by the
 * transformation path that actually reads the bytes, so discovery does not pay
 * for a byte read on every class it observes.
 */
public final class ClassIdentities {

    private ClassIdentities() {
    }

    public static ClassIdentity of(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return new ClassIdentity(type.getName(), ClassLoaderIdentity.idOf(type.getClassLoader()));
    }

    public static ClassIdentity of(String binaryClassName, ClassLoader classLoader) {
        if (binaryClassName == null || binaryClassName.isBlank()) {
            throw new IllegalArgumentException("binaryClassName must not be blank");
        }
        return new ClassIdentity(binaryClassName, ClassLoaderIdentity.idOf(classLoader));
    }

    /**
     * Derive the V1.5 {@link ClassMetadata} enrichment for a live class. The
     * identity pair is the stable key; the remaining fields are observed here
     * from reflection and may be null when the JVM does not expose them (e.g.
     * bootstrap classes have no code source). {@code bytecodeHash} is null and
     * is stamped separately by the transformation path.
     */
    public static ClassMetadata metadataOf(Class<?> type, SupportLevel supportLevel) {
        Objects.requireNonNull(type, "type");
        ClassLoader loader = type.getClassLoader();
        String loaderId = ClassLoaderIdentity.idOf(loader);
        ClassIdentity identity = new ClassIdentity(type.getName(), loaderId);
        String loaderClassName = loader == null ? "bootstrap" : loader.getClass().getName();
        String parentLoaderId = loader == null ? null : ClassLoaderIdentity.idOf(loader.getParent());
        Module module = type.getModule();
        String moduleName = module == null ? null : module.getName();
        boolean namedModule = module != null && module.isNamed() && moduleName != null;
        String codeSource = codeSourceOf(type);
        String protectionDomainSummary = protectionDomainSummaryOf(type);
        return ClassMetadata.builder()
                .identity(identity)
                .loaderClassName(loaderClassName)
                .parentLoaderId(parentLoaderId)
                .moduleName(moduleName)
                .namedModule(namedModule)
                .codeSource(codeSource)
                .protectionDomainSummary(protectionDomainSummary)
                .supportLevel(supportLevel == null ? SupportLevel.SUPPORTED : supportLevel)
                .build();
    }

    /** Stamp a bytecode hash onto a copy of the metadata (transformation path). */
    public static ClassMetadata withBytecodeHash(ClassMetadata metadata, String bytecodeHash) {
        if (metadata == null) {
            return null;
        }
        return metadata.toBuilder().bytecodeHash(bytecodeHash).build();
    }

    private static String codeSourceOf(Class<?> type) {
        try {
            ProtectionDomain pd = type.getProtectionDomain();
            if (pd == null || pd.getCodeSource() == null || pd.getCodeSource().getLocation() == null) {
                return null;
            }
            return pd.getCodeSource().getLocation().toString();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String protectionDomainSummaryOf(Class<?> type) {
        try {
            ProtectionDomain pd = type.getProtectionDomain();
            if (pd == null) {
                return null;
            }
            Module module = type.getModule();
            ModuleDescriptor descriptor = module != null ? module.getDescriptor() : null;
            String modulePart = descriptor != null && descriptor.name() != null ? descriptor.name() : "unnamed";
            String loaderPart = type.getClassLoader() == null ? "bootstrap"
                    : type.getClassLoader().getClass().getName();
            String cs = codeSourceOf(type);
            return "module=" + modulePart + ";loader=" + loaderPart
                    + ";codeSource=" + (cs == null ? "unknown" : cs);
        } catch (SecurityException ignored) {
            return null;
        }
    }
}
