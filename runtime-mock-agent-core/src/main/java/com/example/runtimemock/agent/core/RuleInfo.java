package com.example.runtimemock.agent.core;

import com.example.runtimemock.api.InvokePhase;

public record RuleInfo(
        String id,
        long version,
        String name,
        String description,
        String classId,
        String className,
        String classLoaderId,
        String methodName,
        String methodDescriptor,
        InvokePhase phase,
        int priority,
        int percentage,
        long maxHits,
        long expireAt,
        boolean failOpen,
        boolean enabled,
        long hits,
        long errors,
        boolean locked,
        String scriptHash
) {
}
