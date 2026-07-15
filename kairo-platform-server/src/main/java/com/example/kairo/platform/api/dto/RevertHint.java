package com.example.kairo.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Structured revert guidance returned by {@code POST /api/v1/rules/preview}
 * (V1.6 &sect;2.3 "撤销或恢复链接"). Tells the caller how to undo the enhancement
 * before it is ever applied, so the preview is a complete, reversible plan.
 *
 * @param strategy    stable strategy code, e.g. {@code DISABLE_RULE_VERSION}
 * @param description human-readable summary
 * @param steps       ordered, machine-readable revert steps (endpoint or action)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RevertHint(
        String strategy,
        String description,
        List<String> steps
) {
}
