package com.example.kairo.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed target matcher inside a {@link RuleTargetDto} (V1.6 &sect;2.2). Carries the
 * stable {@code classId}/{@code classLoaderId}/{@code descriptor} identity keys; any
 * extra selector keys are captured verbatim for an exact round-trip.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RuleTargetMatcherDto {

    private String classId;
    private String classLoaderId;
    private String descriptor;
    private final Map<String, Object> additional = new LinkedHashMap<>();

    @JsonCreator
    public RuleTargetMatcherDto(@JsonProperty("classId") String classId,
                                @JsonProperty("classLoaderId") String classLoaderId,
                                @JsonProperty("descriptor") String descriptor) {
        this.classId = classId;
        this.classLoaderId = classLoaderId;
        this.descriptor = descriptor;
    }

    @JsonProperty("classId")
    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    @JsonProperty("classLoaderId")
    public String getClassLoaderId() {
        return classLoaderId;
    }

    public void setClassLoaderId(String classLoaderId) {
        this.classLoaderId = classLoaderId;
    }

    @JsonProperty("descriptor")
    public String getDescriptor() {
        return descriptor;
    }

    public void setDescriptor(String descriptor) {
        this.descriptor = descriptor;
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
