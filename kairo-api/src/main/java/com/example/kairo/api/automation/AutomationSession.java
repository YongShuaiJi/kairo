package com.example.kairo.api.automation;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.write.RiskLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/**
 * The AI/automation top-level boundary (V1.6 &sect;4.1). A session is NOT a new
 * permission principal: it can only narrow the token's existing permissions,
 * never widen them (the {@code maxCapabilityProfile} is the minimum of the token's
 * scope and the requested profile).
 *
 * @param sessionId            stable id
 * @param caller               human/machine identity that owns the session
 * @param source               origin, e.g. {@code mcp}, {@code cli}, {@code sdk}, {@code web}
 * @param applicationId        scope: application
 * @param environmentId        scope: environment (nullable)
 * @param instanceId           scope: single instance (nullable)
 * @param agentId              target agent (nullable; derived from instance when omitted)
 * @param maxCapabilityProfile narrowest allowed script tier
 * @param ttlMillis            relative TTL
 * @param deadlineMillis       absolute deadline epoch millis
 * @param status               {@link AutomationSessionStatus}
 * @param riskLevel            current aggregate risk of resources created in the session
 * @param createdResources     resources (script sessions, rule versions, operations) created here
 * @param cleanupResult        structured outcome of TTL/revert cleanup
 * @param correlationId        links to audit/log
 * @param version              optimistic-lock version
 * @param createdAt            epoch millis
 * @param updatedAt            epoch millis
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutomationSession(
        String sessionId,
        String caller,
        String source,
        String applicationId,
        String environmentId,
        String instanceId,
        String agentId,
        CapabilityProfile maxCapabilityProfile,
        long ttlMillis,
        long deadlineMillis,
        AutomationSessionStatus status,
        RiskLevel riskLevel,
        List<AutomationSessionResource> createdResources,
        java.util.Map<String, Object> cleanupResult,
        String correlationId,
        long version,
        long createdAt,
        long updatedAt
) {
    public AutomationSession {
        sessionId = requireText(sessionId, "sessionId");
        caller = requireText(caller, "caller");
        source = requireText(source, "source");
        applicationId = requireText(applicationId, "applicationId");
        Objects.requireNonNull(maxCapabilityProfile, "maxCapabilityProfile");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0");
        }
        if (deadlineMillis <= 0) {
            throw new IllegalArgumentException("deadlineMillis must be > 0");
        }
        Objects.requireNonNull(status, "status");
        riskLevel = riskLevel == null ? RiskLevel.LOW : riskLevel;
        createdResources = createdResources == null ? List.of() : List.copyOf(createdResources);
        cleanupResult = cleanupResult == null ? java.util.Map.of() : java.util.Map.copyOf(cleanupResult);
        correlationId = correlationId == null ? "" : correlationId;
        if (version < 0) {
            version = 0;
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isTerminal() {
        return status == AutomationSessionStatus.COMPLETED
                || status == AutomationSessionStatus.EXPIRED
                || status == AutomationSessionStatus.REVERTED
                || status == AutomationSessionStatus.FAILED;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
