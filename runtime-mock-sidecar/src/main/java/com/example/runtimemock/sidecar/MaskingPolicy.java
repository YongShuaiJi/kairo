package com.example.runtimemock.sidecar;

import java.util.Map;
import java.util.Set;

public record MaskingPolicy(
        Set<String> allowedFields,
        Map<String, MaskingAction> fieldActions,
        MaskingAction defaultSensitiveAction,
        String fixedValue,
        int maxDepth,
        int maxCollectionSize,
        int maxStringLength
) {
    public static MaskingPolicy productionDefault() {
        return new MaskingPolicy(
                Set.of(),
                Map.of(),
                MaskingAction.MASK,
                "***",
                8,
                1_000,
                64 * 1024
        );
    }

    boolean isAllowed(String fieldPath) {
        return allowedFields.isEmpty() || allowedFields.contains(fieldPath);
    }

    MaskingAction actionFor(String fieldPath, boolean sensitive) {
        MaskingAction explicit = fieldActions.get(fieldPath);
        if (explicit != null) {
            return explicit;
        }
        return sensitive ? defaultSensitiveAction : null;
    }
}
