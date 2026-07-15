package com.example.kairo.api.error;

import java.util.Objects;

/**
 * A machine-readable recovery hint returned with an error (V1.6 &sect;2.4
 * "suggestedActions"). AI clients may surface these to the user or act on them
 * directly when {@code safe} is {@code true}.
 *
 * @param action      stable action code, e.g. {@code REFRESH_RESOURCE}, {@code REQUEST_PREVIEW}
 * @param description human-readable explanation of the suggested action
 * @param href        optional link to a resource that satisfies the action
 * @param safe        whether an automated client may perform the action without human confirmation
 */
public record SuggestedAction(String action, String description, String href, boolean safe) {

    public SuggestedAction {
        action = requireText(action, "action");
        description = description == null ? "" : description;
        href = href == null ? "" : href;
    }

    public static SuggestedAction safe(String action, String description) {
        return new SuggestedAction(action, description, "", true);
    }

    public static SuggestedAction manual(String action, String description) {
        return new SuggestedAction(action, description, "", false);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
