package com.example.kairo.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Strongly-typed request for {@code POST /api/v1/rules/{id}/versions} (V1.6 &sect;2.2).
 * A version inherits the rule's application/environment scope, so only the
 * version-relevant fields ({@code script}/{@code matcher}/{@code targets}/
 * {@code capabilities}/{@code riskLevel}/{@code versionStatus}) are read by the
 * service; the remaining fields are accepted for caller symmetry with {@link
 * CreateRuleRequest} and ignored. Carries the same typed {@link RuleScriptDto}/
 * {@link RuleMatcherDto}/{@link RuleTargetDto} containers.
 *
 * @param versionStatus        version status (optional; defaults to ENABLED)
 * @param riskLevel            machine-readable risk (optional; defaults to MEDIUM)
 * @param script               typed script body
 * @param matcher              typed version-level matcher
 * @param targets              typed enhancement targets
 * @param target               legacy single-target form
 * @param capabilities         capability allow-list
 * @param scriptHash           caller-supplied script hash (optional)
 * @param governance           governance metadata (optional)
 * @param reason               audit reason (optional)
 * @param name                 ignored (inherited from rule); accepted for symmetry
 * @param applicationId        ignored (inherited); accepted for symmetry
 * @param environmentId        ignored (inherited); accepted for symmetry
 * @param status               ignored (aggregate is rule-scoped); accepted for symmetry
 * @param businessCode         ignored at version scope; accepted for symmetry
 * @param businessAbbreviation ignored at version scope; accepted for symmetry
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateRuleVersionRequest(
        String versionStatus,
        String riskLevel,
        RuleScriptDto script,
        RuleMatcherDto matcher,
        List<RuleTargetDto> targets,
        RuleTargetDto target,
        List<String> capabilities,
        String scriptHash,
        Map<String, Object> governance,
        String reason,
        String name,
        String applicationId,
        String environmentId,
        String status,
        String businessCode,
        String businessAbbreviation
) {
}
