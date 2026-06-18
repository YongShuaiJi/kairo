package com.example.runtimemock.agent.core;

import java.util.List;
import java.util.Map;

public record ResetClassResult(
        String classId,
        List<String> removedRuleIds,
        Map<String, String> failedRules,
        List<RuleInfo> remainingRules,
        boolean degraded
) {
}
