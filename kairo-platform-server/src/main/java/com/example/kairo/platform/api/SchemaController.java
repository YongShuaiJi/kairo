package com.example.kairo.platform.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable schema bundle for AI/SDK discovery (V1.6 &sect;1
 * "machine-readable schemas" / &sect;4.3 "脚本 API schema"). Returns the frozen
 * V1 contract shapes so a model can program against the API without scraping
 * prose. The full OpenAPI document is at {@code /v3/api-docs}.
 */
@RestController
@RequestMapping("/api/v1/schemas")
@ConditionalOnProperty(prefix = "kairo.platform.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class SchemaController {

    @GetMapping
    public Map<String, Object> schemas() {
        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put("errorModel", errorModel());
        schemas.put("scriptApiSurface", scriptApiSurface());
        schemas.put("operationStatus", Map.of("enum", List.of(
                "PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "REVERTED", "TIMEOUT")));
        schemas.put("automationSessionStatus", Map.of("enum", List.of(
                "CREATED", "ACTIVE", "COMPLETED", "EXPIRED", "REVERTED", "FAILED")));
        schemas.put("riskLevel", Map.of("enum", List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")));
        schemas.put("errorCategory", Map.of("enum", List.of(
                "VALIDATION", "AUTHENTICATION", "AUTHORIZATION", "NOT_FOUND", "CONFLICT",
                "CAPABILITY", "BUSINESS_RULE", "RATE_LIMITED", "OPERATION_IN_PROGRESS", "INTERNAL")));
        schemas.put("openapiDocument", "/v3/api-docs");
        return schemas;
    }

    private Map<String, Object> errorModel() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("code", Map.of("type", "string"));
        props.put("message", Map.of("type", "string"));
        props.put("category", Map.of("type", "string", "enumReference", "errorCategory"));
        props.put("retryable", Map.of("type", "boolean"));
        props.put("field", Map.of("type", "string", "nullable", true));
        props.put("path", Map.of("type", "string", "nullable", true));
        props.put("location", Map.of("type", "string", "enum", List.of("body", "query", "header", "path")));
        props.put("details", Map.of("type", "object", "additionalProperties", true));
        props.put("suggestedActions", Map.of("type", "array", "items", Map.of(
                "type", "object", "properties", Map.of(
                        "action", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "href", Map.of("type", "string", "nullable", true),
                        "safe", Map.of("type", "boolean")))));
        props.put("correlationId", Map.of("type", "string"));
        return Map.of("type", "object", "properties", props,
                "required", List.of("code", "message", "category", "retryable", "correlationId"));
    }

    private Map<String, Object> scriptApiSurface() {
        return Map.of(
                "type", "object",
                "description", "The script API an AI client programs against within an AutomationSession",
                "properties", Map.of(
                        "allowedProfile", Map.of("type", "string", "enum", List.of("SAFE", "EXTENDED", "UNRESTRICTED")),
                        "schema", Map.of("type", "object", "description", "JSON Schema for the trial script input/output"),
                        "examples", Map.of("type", "array"),
                        "diagnosticsFormat", Map.of("type", "object"),
                        "limits", Map.of("type", "object")));
    }
}
