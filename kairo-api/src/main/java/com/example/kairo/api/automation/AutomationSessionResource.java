package com.example.kairo.api.automation;

import java.util.Objects;

/**
 * A resource created within an {@link AutomationSession} (V1.6 &sect;4.1 / &sect;5.1
 * {@code automation_session_resource}). Tracked so one-click revert knows what to undo.
 *
 * @param resourceType  {@code script-session}, {@code rule-version}, {@code operation}, ...
 * @param resourceId    stable id of the created resource
 * @param reversible    whether this resource can be reverted
 * @param createdAt     epoch millis
 */
public record AutomationSessionResource(String resourceType, String resourceId, boolean reversible, long createdAt) {
    public AutomationSessionResource {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        if (createdAt < 0) {
            createdAt = 0;
        }
    }
}
