package com.example.kairo.platform.api.dto;

import com.example.kairo.api.write.ImpactSummary;
import com.example.kairo.api.write.RiskLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Canonical server-assembled rule preview (V1.6 &sect;2.3 / &sect;5.3). The platform
 * owns the business defaults (status, risk, capabilities, target/matcher shape) and
 * returns the exact typed payload the caller should POST to {@code /api/v1/rules} or
 * {@code /api/v1/rules/{id}/versions}, together with a preview token/revision,
 * structured impact/risk, the script validation result, and revert guidance.
 *
 * <p>This removes client-side rule assembly: the web workbench and AI clients call
 * preview, then forward {@code payload} verbatim to the create endpoint.
 *
 * @param payload      canonical {@link CreateRuleRequest} with server-computed defaults
 * @param previewToken opaque token proving the caller saw this preview (V1.6 &sect;5.4)
 * @param revision     previewed resource revision
 * @param riskLevel    machine-readable risk
 * @param impact       structured impact range
 * @param validation   {@link com.example.kairo.platform.service.ScriptWorkbenchService#validate} result
 * @param revert       how to undo the enhancement
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RulePreviewResponse(
        CreateRuleRequest payload,
        String previewToken,
        long revision,
        RiskLevel riskLevel,
        ImpactSummary impact,
        Map<String, Object> validation,
        RevertHint revert
) {
}
