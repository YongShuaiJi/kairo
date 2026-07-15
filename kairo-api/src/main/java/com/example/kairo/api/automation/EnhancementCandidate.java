package com.example.kairo.api.automation;

import com.example.kairo.api.ProxyType;
import com.example.kairo.api.SupportLevel;

import java.util.Objects;

/**
 * One candidate enhancement target returned by {@code resolve-targets}
 * (V1.6 &sect;4.3 "候选方法及置信度和匹配理由").
 *
 * @param targetId      stable target id to reference in subsequent promote/apply steps
 * @param className     JVM internal/qualified class name
 * @param methodName    method name
 * @param descriptor    precise JVM method descriptor, e.g. {@code (Ljava/lang/String;)I}
 * @param classLoaderId class loader that owns the class
 * @param confidence    0.0..1.0 confidence that this is the intended target
 * @param matchReason   machine-readable reason code, e.g. {@code name-exact}, {@code signature-near}
 * @param supportLevel  {@link SupportLevel} the agent reported
 * @param proxyType     detected {@link ProxyType}
 */
public record EnhancementCandidate(
        String targetId,
        String className,
        String methodName,
        String descriptor,
        String classLoaderId,
        double confidence,
        String matchReason,
        SupportLevel supportLevel,
        ProxyType proxyType
) {
    public EnhancementCandidate {
        targetId = requireText(targetId, "targetId");
        className = requireText(className, "className");
        methodName = requireText(methodName, "methodName");
        descriptor = requireText(descriptor, "descriptor");
        classLoaderId = requireText(classLoaderId, "classLoaderId");
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
        matchReason = matchReason == null ? "" : matchReason;
        supportLevel = supportLevel == null ? SupportLevel.SUPPORTED : supportLevel;
        proxyType = proxyType == null ? ProxyType.PLAIN : proxyType;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
