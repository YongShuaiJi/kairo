package com.example.kairo.api.matrix;

import com.example.kairo.api.SupportLevel;

import java.util.Objects;

/**
 * One declared scenario in the V1.5 compatibility matrix (&sect;2 / &sect;6).
 *
 * <p>A scenario is a named cell of the matrix with a declared
 * {@link SupportLevel}, the category it belongs to, the JDK range it requires
 * (when relevant) and whether it is exercised automatically on the running JDK
 * or only documented. The matrix fixture enumerates these; the report records an
 * outcome against each.
 */
public final class CompatibilityScenario {

    private final String id;
    private final String name;
    private final CompatibilityCategory category;
    private final SupportLevel supportLevel;
    private final String jdkRequirement;
    private final boolean automated;
    private final String description;

    public CompatibilityScenario(String id, String name, CompatibilityCategory category,
                                 SupportLevel supportLevel, String jdkRequirement,
                                 boolean automated, String description) {
        this.id = requireText(id, "id");
        this.name = requireText(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.supportLevel = Objects.requireNonNull(supportLevel, "supportLevel");
        this.jdkRequirement = jdkRequirement;
        this.automated = automated;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public CompatibilityCategory category() {
        return category;
    }

    public SupportLevel supportLevel() {
        return supportLevel;
    }

    public String jdkRequirement() {
        return jdkRequirement;
    }

    public boolean automated() {
        return automated;
    }

    public String description() {
        return description;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
