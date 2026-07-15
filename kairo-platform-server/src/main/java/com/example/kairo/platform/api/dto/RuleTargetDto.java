package com.example.kairo.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed enhancement target inside a rule/version payload (V1.6 &sect;2.2 / V1.3 &sect;3.5).
 * Carries the stable {@code protocol}/{@code className}/{@code methodName} identity,
 * the nested {@link RuleTargetMatcherDto}, and the V1.3 enhancement-location fields
 * ({@code location}/{@code callSiteSelector}). Extra keys are captured verbatim.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RuleTargetDto {

    private String protocol;
    private String className;
    private String methodName;
    private RuleTargetMatcherDto matcher;
    private String location;
    private Map<String, Object> callSiteSelector;
    private final Map<String, Object> additional = new LinkedHashMap<>();

    @JsonCreator
    public RuleTargetDto(@JsonProperty("protocol") String protocol,
                         @JsonProperty("className") String className,
                         @JsonProperty("methodName") String methodName,
                         @JsonProperty("matcher") RuleTargetMatcherDto matcher,
                         @JsonProperty("location") String location,
                         @JsonProperty("callSiteSelector") Map<String, Object> callSiteSelector) {
        this.protocol = protocol;
        this.className = className;
        this.methodName = methodName;
        this.matcher = matcher;
        this.location = location;
        this.callSiteSelector = callSiteSelector;
    }

    @JsonProperty("protocol")
    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    @JsonProperty("className")
    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    @JsonProperty("methodName")
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    @JsonProperty("matcher")
    public RuleTargetMatcherDto getMatcher() {
        return matcher;
    }

    public void setMatcher(RuleTargetMatcherDto matcher) {
        this.matcher = matcher;
    }

    @JsonProperty("location")
    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    @JsonProperty("callSiteSelector")
    public Map<String, Object> getCallSiteSelector() {
        return callSiteSelector;
    }

    public void setCallSiteSelector(Map<String, Object> callSiteSelector) {
        this.callSiteSelector = callSiteSelector;
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
