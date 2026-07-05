package com.example.kairo.agent.core;

public record ClassInfo(
        String classId,
        String className,
        String classLoaderId,
        String classLoaderClassName,
        boolean modifiable
) {
}
