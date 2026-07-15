package com.example.kairo.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Strongly-typed request for {@code POST /api/v1/rules} (V1.6 &sect;2.2 / &sect;5.1:
 * replace {@code Map<String,Object>} on the rule create path with an explicit DTO so
 * the OpenAPI contract publishes a concrete schema for {@code targets}, {@code script}
 * and {@code matcher}). The controller binds this record directly from the JSON body
 * and converts it to the Map the service layer consumes; the service boundary is
 * unchanged, so existing callers and integration tests remain compatible.
 *
 * <p>The script/matcher/target containers are {@link RuleScriptDto}/{@link
 * RuleMatcherDto}/{@link RuleTargetDto}: they expose the canonical typed fields and
 * also round-trip any legacy declarative keys verbatim, so the opaque object the
 * agent receives is identical to the previous Map-body form.
 *
 * @param id                   rejected when present (ids are platform-generated); here so the
 *                           reject check still fires for clients that try to set it
 * @param name                 rule name (required)
 * @param applicationId        owning application (required)
 * @param environmentId        assigned environment (required)
 * @param status               aggregate lifecycle status (optional; defaults to ENABLED)
 * @param versionStatus        initial version status (optional; defaults to ENABLED)
 * @param riskLevel            machine-readable risk (optional; defaults to MEDIUM)
 * @param script               typed script body (phase + script, or legacy declarative shape)
 * @param matcher              typed version-level matcher (phase)
 * @param targets              typed enhancement targets (protocol/className/methodName/matcher)
 * @param target               legacy single-target form (used only when {@code targets} is empty)
 * @param capabilities         capability allow-list
 * @param businessCode         business abbreviation for id generation (optional)
 * @param businessAbbreviation alias for {@code businessCode} (optional)
 * @param scriptHash           caller-supplied script hash (optional; computed when absent)
 * @param governance           governance metadata (optional)
 * @param reason               audit reason (optional)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateRuleRequest(
        String id,
        String name,
        String applicationId,
        String environmentId,
        String status,
        String versionStatus,
        String riskLevel,
        RuleScriptDto script,
        RuleMatcherDto matcher,
        List<RuleTargetDto> targets,
        RuleTargetDto target,
        List<String> capabilities,
        String businessCode,
        String businessAbbreviation,
        String scriptHash,
        Map<String, Object> governance,
        String reason
) {
}
