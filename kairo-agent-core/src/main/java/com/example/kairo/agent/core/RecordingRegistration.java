package com.example.kairo.agent.core;

public record RecordingRegistration(
        String sessionId,
        String classId,
        String className,
        String methodName,
        String methodDescriptor
) {
}
