package com.example.runtimemock.agent.core;

public record RecordingRegistration(
        String sessionId,
        String classId,
        String className,
        String methodName,
        String methodDescriptor
) {
}
