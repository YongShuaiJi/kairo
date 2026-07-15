package com.example.kairo.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Minimal, typed input for {@code POST /api/v1/rules/preview} (V1.6 &sect;5.3). The
 * caller supplies intent (name, scope, target identity, phase, script source); the
 * platform assembles the canonical rule payload with server-computed defaults,
 * validates the script, and returns impact/risk/revert metadata. The web workbench
 * and AI clients use this instead of assembling business defaults client-side.
 *
 * @param name            rule name (required)
 * @param applicationId   owning application (required)
 * @param environmentId   assigned environment (required)
 * @param classId         runtime class id from target discovery (optional; manual entry allowed)
 * @param className       binary class name (required)
 * @param classLoaderId   class loader identity (required)
 * @param methodName      target method name (required)
 * @param methodDescriptor JVM method descriptor, e.g. {@code (I)I} (required)
 * @param executionPhase  BEFORE / RETURN / THROWS (required)
 * @param script          groovy script source (required)
 * @param reason          audit reason (optional)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RulePreviewRequest(
        String name,
        String applicationId,
        String environmentId,
        String classId,
        String className,
        String classLoaderId,
        String methodName,
        String methodDescriptor,
        String executionPhase,
        String script,
        String reason
) {
}
