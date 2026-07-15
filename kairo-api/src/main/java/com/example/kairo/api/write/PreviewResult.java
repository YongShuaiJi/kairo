package com.example.kairo.api.write;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.Objects;

/**
 * Structured result of a preview/dry-run (V1.6 &sect;2.3 "dry-run/preview" +
 * "结构化影响范围" + "机器可读风险等级"). A preview is required before any
 * high-risk apply; the apply must carry back the {@code previewToken} and
 * {@code revision} to prove the caller saw the impact (V1.6 &sect;5.4).
 *
 * @param previewToken  opaque token the apply step must echo
 * @param revision      previewed resource revision the apply must match
 * @param riskLevel     machine-readable risk
 * @param impact        structured impact range
 * @param diff          structured diff/preview payload (resource-specific)
 * @param expiresAt     epoch millis after which this preview token is invalid
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreviewResult(
        String previewToken,
        long revision,
        RiskLevel riskLevel,
        ImpactSummary impact,
        Map<String, Object> diff,
        long expiresAt
) {
    public PreviewResult {
        previewToken = Objects.requireNonNull(previewToken, "previewToken");
        if (previewToken.isBlank()) {
            throw new IllegalArgumentException("previewToken must not be blank");
        }
        riskLevel = riskLevel == null ? RiskLevel.LOW : riskLevel;
        impact = impact == null
                ? new ImpactSummary(java.util.List.of(), "", "", true, 0)
                : impact;
        diff = diff == null ? Map.of() : Map.copyOf(diff);
        if (expiresAt < 0) {
            expiresAt = 0;
        }
    }
}
