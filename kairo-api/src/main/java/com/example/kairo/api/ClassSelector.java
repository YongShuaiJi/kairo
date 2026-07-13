package com.example.kairo.api;

import java.util.Objects;

/**
 * Selector for a class that may not yet be loaded (V1.5 &sect;4.1 / &sect;5).
 *
 * <p>An {@link EnhancementTarget} addresses a <em>resolved</em> class by binary
 * name and stable {@code classLoaderId}. Before the class is loaded there is no
 * loader id to carry, so pre-registration of a pending rule uses a
 * {@code ClassSelector}: a fuzzy matcher the agent applies to each class the JVM
 * loads. When a newly loaded class matches, the transformer builds the V1.4 rule
 * chain for it and reports the actual {@link com.example.kairo.api.bytecode.ClassIdentity}.
 *
 * <p>A selector is <em>exact</em> when {@link #classLoaderId()} is set: it matches
 * at most one loaded class. A <em>fuzzy</em> selector leaves the loader id null
 * and may match several classes in different loaders; in that case the agent
 * refuses to enhance unless {@link #allMatch()} is true, so a fuzzy rule never
 * silently enhances the wrong target (&sect;4.1: "匹配多项时按策略拒绝或显式 all-match").
 * The optional {@code loaderClassName}, {@code codeSource} and {@code moduleName}
 * narrow the match so a cross-restart rule re-resolves without assuming an old
 * loader id is still valid.
 */
public final class ClassSelector {

    private final String className;
    private final String classLoaderId;
    private final String loaderClassName;
    private final String codeSource;
    private final String moduleName;
    private final boolean allMatch;

    public ClassSelector(String className, String classLoaderId, String loaderClassName,
                         String codeSource, String moduleName, boolean allMatch) {
        this.className = requireText(className, "className");
        this.classLoaderId = blankToNull(classLoaderId);
        this.loaderClassName = blankToNull(loaderClassName);
        this.codeSource = blankToNull(codeSource);
        this.moduleName = blankToNull(moduleName);
        this.allMatch = allMatch;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Binary class name to match. */
    public String className() {
        return className;
    }

    /** Stable loader id for an exact selector; {@code null} for a fuzzy selector. */
    public String classLoaderId() {
        return classLoaderId;
    }

    /** Whether this selector is exact (carries a loader id). */
    public boolean isExact() {
        return classLoaderId != null;
    }

    /** Optional loader class name narrowing (e.g. {@code TomcatEmbeddedWebappClassLoader}). */
    public String loaderClassName() {
        return loaderClassName;
    }

    /** Optional code source URL narrowing. */
    public String codeSource() {
        return codeSource;
    }

    /** Optional module name narrowing. */
    public String moduleName() {
        return moduleName;
    }

    /** Whether a fuzzy multi-match may enhance every match (explicit all-match). */
    public boolean allMatch() {
        return allMatch;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static final class Builder {
        private String className;
        private String classLoaderId;
        private String loaderClassName;
        private String codeSource;
        private String moduleName;
        private boolean allMatch;

        private Builder() {
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder classLoaderId(String classLoaderId) {
            this.classLoaderId = classLoaderId;
            return this;
        }

        public Builder loaderClassName(String loaderClassName) {
            this.loaderClassName = loaderClassName;
            return this;
        }

        public Builder codeSource(String codeSource) {
            this.codeSource = codeSource;
            return this;
        }

        public Builder moduleName(String moduleName) {
            this.moduleName = moduleName;
            return this;
        }

        public Builder allMatch(boolean allMatch) {
            this.allMatch = allMatch;
            return this;
        }

        public ClassSelector build() {
            return new ClassSelector(className, classLoaderId, loaderClassName, codeSource, moduleName, allMatch);
        }
    }
}
