package com.example.kairo.agent.core;

public record RuntimeMetrics(
        int loadedClassCount,
        int enhancedClassCount,
        int enhancedMethodCount,
        int totalRuleCount,
        int activeRuleCount,
        long totalHits,
        long totalErrors,
        boolean globallyEnabled
) {
}
