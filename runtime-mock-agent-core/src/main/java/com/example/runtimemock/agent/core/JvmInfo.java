package com.example.runtimemock.agent.core;

public record JvmInfo(
        String applicationName,
        long pid,
        String host,
        String javaVersion,
        long startTimeMillis,
        String agentVersion,
        String loadMode,
        String status,
        int enhancedClassCount,
        int enhancedMethodCount,
        int activeRuleCount
) {
}
