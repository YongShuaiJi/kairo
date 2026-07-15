package com.example.kairo.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed script body for a rule/version payload (V1.6 &sect;2.2). The canonical
 * script object carries {@code phase} + {@code script}; the platform also accepts
 * legacy declarative shapes ({@code {type,value}} / {@code {type,exception}}), so
 * unknown keys are captured verbatim and re-serialized at the top level. This keeps
 * the typed contract (OpenAPI publishes {@code phase}/{@code script}) while the
 * service layer still receives the exact opaque object it stores for the agent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RuleScriptDto {

    private String phase;
    private String script;
    private final Map<String, Object> additional = new LinkedHashMap<>();

    @JsonCreator
    public RuleScriptDto(@JsonProperty("phase") String phase,
                         @JsonProperty("script") String script) {
        this.phase = phase;
        this.script = script;
    }

    @JsonProperty("phase")
    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    @JsonProperty("script")
    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    /** Capture any non-canonical key (e.g. {@code type}/{@code value}) for verbatim round-trip. */
    @JsonAnySetter
    public void addAdditional(String key, Object value) {
        additional.put(key, value);
    }

    /** Merge the captured keys back at the top level on serialization. */
    @JsonAnyGetter
    public Map<String, Object> additional() {
        return additional;
    }
}
