package com.example.kairo.api.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * V1.7 M1-C &sect;8.3: one rule in a runtime-state snapshot. Carries only stable rule identity
 * (id, version, enabled, expiry); it never includes the rule script, script source, token or any
 * sensitive payload. {@code expireAt} is epoch millis ({@code 0} means no expiry), mirroring the
 * Agent's {@code MockRule.expireAt} sentinel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleSnapshot(
        @JsonProperty("ruleId") String ruleId,
        @JsonProperty("ruleVersion") long ruleVersion,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("expireAt") long expireAt) {
}
