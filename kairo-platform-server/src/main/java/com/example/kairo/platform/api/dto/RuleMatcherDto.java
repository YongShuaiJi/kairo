package com.example.kairo.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed version-level matcher for a rule/version payload (V1.6 &sect;2.2). The
 * canonical matcher carries {@code phase}; extra selector keys are captured and
 * re-serialized verbatim so the opaque matcher the agent receives is unchanged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RuleMatcherDto {

    private String phase;
    private final Map<String, Object> additional = new LinkedHashMap<>();

    @JsonCreator
    public RuleMatcherDto(@JsonProperty("phase") String phase) {
        this.phase = phase;
    }

    @JsonProperty("phase")
    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    @JsonAnySetter
    public void addAdditional(String key, Object value) {
        additional.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> additional() {
        return additional;
    }
}
