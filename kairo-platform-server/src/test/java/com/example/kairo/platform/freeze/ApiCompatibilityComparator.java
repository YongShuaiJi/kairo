package com.example.kairo.platform.freeze;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Semantic, additive-only compatibility comparison for the frozen public V1 OpenAPI model. */
final class ApiCompatibilityComparator {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private ApiCompatibilityComparator() {
    }

    static List<String> compare(FreezeModels.NormalizedApi baseline,
                                FreezeModels.NormalizedApi current) {
        List<String> violations = new ArrayList<>();

        Map<String, FreezeModels.OperationSnapshot> liveOps = new TreeMap<>();
        for (FreezeModels.OperationSnapshot operation : list(current.operations())) {
            liveOps.put(operation.method() + " " + operation.path(), operation);
        }
        for (FreezeModels.OperationSnapshot expected : list(baseline.operations())) {
            String key = expected.method() + " " + expected.path();
            FreezeModels.OperationSnapshot actual = liveOps.get(key);
            if (actual == null) {
                violations.add("FROZEN API OPERATION REMOVED: " + key);
            } else {
                compareOperation(violations, key, expected, actual);
            }
        }

        Map<String, FreezeModels.SchemaSnapshot> liveSchemas = new TreeMap<>();
        for (FreezeModels.SchemaSnapshot schema : list(current.schemas())) {
            liveSchemas.put(schema.name(), schema);
        }
        for (FreezeModels.SchemaSnapshot expected : list(baseline.schemas())) {
            FreezeModels.SchemaSnapshot actual = liveSchemas.get(expected.name());
            if (actual == null) {
                violations.add("FROZEN SCHEMA REMOVED: " + expected.name());
            } else {
                compareSchema(violations, expected, actual);
            }
        }

        Map<String, FreezeModels.SecuritySchemeSnapshot> liveSchemes = new TreeMap<>();
        for (FreezeModels.SecuritySchemeSnapshot scheme : list(current.securitySchemes())) {
            liveSchemes.put(scheme.name(), scheme);
        }
        for (FreezeModels.SecuritySchemeSnapshot expected : list(baseline.securitySchemes())) {
            FreezeModels.SecuritySchemeSnapshot actual = liveSchemes.get(expected.name());
            if (actual == null) {
                violations.add("FROZEN SECURITY SCHEME REMOVED: " + expected.name());
            } else if (!str(expected.type()).equals(str(actual.type()))
                    || !str(expected.scheme()).equals(str(actual.scheme()))
                    || !str(expected.bearerFormat()).equals(str(actual.bearerFormat()))) {
                violations.add("SECURITY SCHEME CHANGED: " + expected.name());
            }
        }
        return violations;
    }

    private static void compareOperation(List<String> violations, String key,
                                         FreezeModels.OperationSnapshot expected,
                                         FreezeModels.OperationSnapshot actual) {
        compareSecurity(violations, key, expected.security(), actual.security());
        if (!str(expected.authorization()).equals(str(actual.authorization()))) {
            violations.add(key + " authorization requirement changed: "
                    + str(expected.authorization()) + " -> " + str(actual.authorization()));
        }
        compareDeprecation(violations, key, expected, actual);

        if (!expected.requestBodyRequired() && actual.requestBodyRequired()) {
            violations.add(key + " requestBody became REQUIRED (breaking)");
        }
        compareBodyContract(violations, key + " requestBody",
                expected.requestBodySchema(), actual.requestBodySchema());
        if (!list(actual.requestContentTypes()).containsAll(list(expected.requestContentTypes()))) {
            violations.add(key + " request content type removed: expected "
                    + list(expected.requestContentTypes()) + " live " + list(actual.requestContentTypes()));
        }

        for (FreezeModels.ResponseSnapshot response : list(expected.responses())) {
            FreezeModels.ResponseSnapshot live = list(actual.responses()).stream()
                    .filter(candidate -> candidate.status().equals(response.status()))
                    .findFirst().orElse(null);
            if (live == null) {
                violations.add(key + " response status REMOVED: " + response.status());
            } else {
                compareBodyContract(violations, key + " response " + response.status(),
                        response.schema(), live.schema());
                if (!list(live.contentTypes()).containsAll(list(response.contentTypes()))) {
                    violations.add(key + " response " + response.status()
                            + " content type removed: expected " + list(response.contentTypes())
                            + " live " + list(live.contentTypes()));
                }
            }
        }

        for (FreezeModels.ParamSnapshot parameter : list(expected.parameters())) {
            FreezeModels.ParamSnapshot live = list(actual.parameters()).stream()
                    .filter(candidate -> candidate.name().equals(parameter.name())
                            && candidate.in().equals(parameter.in()))
                    .findFirst().orElse(null);
            if (live == null) {
                violations.add(key + " parameter REMOVED: " + parameter.in() + "/" + parameter.name());
                continue;
            }
            if (!parameter.required() && live.required()) {
                violations.add(key + " parameter became REQUIRED: " + parameter.in() + "/" + parameter.name());
            }
            if (!str(parameter.type()).equals(str(live.type()))
                    || !str(parameter.format()).equals(str(live.format()))
                    || !str(parameter.schemaRef()).equals(str(live.schemaRef()))) {
                violations.add(key + " parameter schema changed: " + parameter.in() + "/" + parameter.name());
            }
            compareParameterContract(violations,
                    key + " parameter " + parameter.in() + "/" + parameter.name(),
                    parameter.schemaContract(), live.schemaContract());
            if (enumNarrowed(parameter.enumValues(), live.enumValues())) {
                violations.add(key + " parameter enum narrowed: " + parameter.in() + "/" + parameter.name());
            }
            if (parameter.nullable() && !live.nullable()) {
                violations.add(key + " parameter nullable->non-null: "
                        + parameter.in() + "/" + parameter.name());
            }
        }
        for (FreezeModels.ParamSnapshot parameter : list(actual.parameters())) {
            if (parameter.required() && list(expected.parameters()).stream().noneMatch(
                    frozen -> frozen.name().equals(parameter.name())
                            && frozen.in().equals(parameter.in()) && frozen.required())) {
                violations.add(key + " NEWLY REQUIRED parameter: "
                        + parameter.in() + "/" + parameter.name());
            }
        }
    }

    private static void compareSecurity(List<String> violations, String key,
                                        List<String> expected, List<String> actual) {
        List<String> frozen = list(expected);
        List<String> live = list(actual);
        if (!frozen.equals(live)) {
            violations.add(key + " authentication requirement changed: "
                    + frozen + " -> " + live);
        }
    }

    private static void compareBodyContract(List<String> violations, String context,
                                            String expected, String actual) {
        if (expected == null) {
            return;
        }
        if (actual == null) {
            violations.add(context + " schema removed");
            return;
        }
        if (!expected.startsWith("{") || !actual.startsWith("{")) {
            if (!str(expected).equals(str(actual))) {
                violations.add(context + " schema changed " + expected + " -> " + actual);
            }
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode frozen = JSON.readTree(expected);
            com.fasterxml.jackson.databind.JsonNode live = JSON.readTree(actual);
            for (String mediaType : fieldNames(frozen)) {
                if (!live.has(mediaType)) {
                    violations.add(context + " schema media type removed: " + mediaType);
                } else {
                    compareSchemaNode(violations, context + "[" + mediaType + "]",
                            frozen.path(mediaType), live.path(mediaType));
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            violations.add(context + " schema contract is invalid JSON: " + e.getMessage());
        }
    }

    private static void compareParameterContract(List<String> violations, String context,
                                                 String expected, String actual) {
        if (expected == null) {
            return;
        }
        if (actual == null) {
            violations.add(context + " schema contract removed");
            return;
        }
        try {
            compareSchemaNode(violations, context, JSON.readTree(expected), JSON.readTree(actual));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            violations.add(context + " schema contract is invalid JSON: " + e.getMessage());
        }
    }

    private static void compareSchemaNode(List<String> violations, String context,
                                          com.fasterxml.jackson.databind.JsonNode frozen,
                                          com.fasterxml.jackson.databind.JsonNode live) {
        for (String scalar : List.of("$ref", "type", "format", "pattern", "default", "multipleOf",
                "minimum", "maximum", "const", "uniqueItems", "readOnly", "writeOnly",
                "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength",
                "minItems", "maxItems", "minProperties", "maxProperties",
                "minContains", "maxContains", "contentEncoding", "contentMediaType")) {
            if (!nodeText(frozen.get(scalar)).equals(nodeText(live.get(scalar)))) {
                violations.add(context + " " + scalar + " changed: "
                        + nodeText(frozen.get(scalar)) + " -> " + nodeText(live.get(scalar)));
            }
        }
        if (frozen.path("nullable").asBoolean(false)
                && !live.path("nullable").asBoolean(false)) {
            violations.add(context + " nullable->non-null");
        }
        List<String> frozenEnum = textValues(frozen.get("enum"));
        List<String> liveEnum = textValues(live.get("enum"));
        if ((frozen.get("enum") == null && live.get("enum") != null)
                || (frozen.get("enum") != null && live.get("enum") != null
                && !liveEnum.containsAll(frozenEnum))) {
            violations.add(context + " enum narrowed: " + frozenEnum + " -> " + liveEnum);
        }

        List<String> frozenRequired = textValues(frozen.get("required"));
        for (String required : textValues(live.get("required"))) {
            if (!frozenRequired.contains(required)) {
                violations.add(context + " property became/newly REQUIRED: " + required);
            }
        }
        com.fasterxml.jackson.databind.JsonNode frozenProperties = frozen.path("properties");
        com.fasterxml.jackson.databind.JsonNode liveProperties = live.path("properties");
        for (String property : fieldNames(frozenProperties)) {
            if (!liveProperties.has(property)) {
                violations.add(context + " property REMOVED: " + property);
            } else {
                compareSchemaNode(violations, context + "." + property,
                        frozenProperties.path(property), liveProperties.path(property));
            }
        }

        compareOptionalNode(violations, context + " items", frozen.get("items"), live.get("items"));
        compareAdditionalPropertiesNode(violations, context + " additionalProperties",
                frozen.get("additionalProperties"), live.get("additionalProperties"));
        compareComposition(violations, context, "allOf", frozen.path("allOf"), live.path("allOf"), false);
        compareComposition(violations, context, "anyOf", frozen.path("anyOf"), live.path("anyOf"), true);
        compareComposition(violations, context, "oneOf", frozen.path("oneOf"), live.path("oneOf"), true);
        compareOptionalNode(violations, context + " not", frozen.get("not"), live.get("not"));
    }

    /**
     * Additive-only additionalProperties comparison for the recursive body/parameter contract.
     * Unlike {@link #compareOptionalNode}, this applies the same openness semantics as the frozen
     * schema/property contract: narrowing (open/true -> false, open -> typed schema, typed -> false)
     * is breaking, while safe relaxations (false -> true/unset, typed schema -> open) are allowed.
     * A typed value schema is compared recursively so $ref/type/shape mutations are caught.
     */
    private static void compareAdditionalPropertiesNode(List<String> violations, String context,
                                                        com.fasterxml.jackson.databind.JsonNode frozen,
                                                        com.fasterxml.jackson.databind.JsonNode live) {
        boolean expectedOpen = isOpenAdditionalProperties(frozen);
        boolean expectedClosed = isClosedAdditionalProperties(frozen);
        boolean expectedTyped = isTypedAdditionalProperties(frozen);
        boolean actualOpen = isOpenAdditionalProperties(live);
        boolean actualClosed = isClosedAdditionalProperties(live);
        boolean actualTyped = isTypedAdditionalProperties(live);
        if (expectedOpen && actualClosed) {
            violations.add(context + " open->closed (breaking): " + frozen + " -> " + live);
        } else if (expectedOpen && actualTyped) {
            violations.add(context + " open->typed (narrowed): " + frozen + " -> " + live);
        } else if (expectedTyped && actualClosed) {
            violations.add(context + " typed->closed (breaking): " + frozen + " -> " + live);
        } else if (expectedTyped && actualTyped) {
            compareSchemaNode(violations, context, frozen, live);
        }
        // Relaxations (closed->open, closed->typed, typed->open) and open<->open: safe, no violation.
    }

    private static boolean isOpenAdditionalProperties(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull()
                || (node.isBoolean() && node.asBoolean());
    }

    private static boolean isClosedAdditionalProperties(com.fasterxml.jackson.databind.JsonNode node) {
        return node != null && node.isBoolean() && !node.asBoolean();
    }

    private static boolean isTypedAdditionalProperties(com.fasterxml.jackson.databind.JsonNode node) {
        return node != null && node.isObject();
    }

    private static void compareOptionalNode(List<String> violations, String context,
                                            com.fasterxml.jackson.databind.JsonNode frozen,
                                            com.fasterxml.jackson.databind.JsonNode live) {
        if (frozen == null && live == null) {
            return;
        }
        if (frozen == null || live == null || frozen.isValueNode() || live.isValueNode()) {
            if (!java.util.Objects.equals(frozen, live)) {
                violations.add(context + " changed: " + frozen + " -> " + live);
            }
            return;
        }
        compareSchemaNode(violations, context, frozen, live);
    }

    private static void compareComposition(List<String> violations, String context, String kind,
                                           com.fasterxml.jackson.databind.JsonNode frozen,
                                           com.fasterxml.jackson.databind.JsonNode live,
                                           boolean allowAdditiveBranches) {
        if (!frozen.isArray()) {
            if (live.isArray()) {
                violations.add(context + " " + kind + " added");
            }
            return;
        }
        if (!live.isArray() || live.size() < frozen.size()
                || (!allowAdditiveBranches && live.size() != frozen.size())) {
            violations.add(context + " " + kind + " changed branch count");
            return;
        }
        for (int i = 0; i < frozen.size(); i++) {
            compareSchemaNode(violations, context + " " + kind + "[" + i + "]",
                    frozen.path(i), live.path(i));
        }
    }

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.fieldNames().forEachRemaining(result::add);
        result.sort(null);
        return result;
    }

    private static List<String> textValues(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static String nodeText(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText(node.toString());
    }

    private static void compareDeprecation(List<String> violations, String key,
                                           FreezeModels.OperationSnapshot expected,
                                           FreezeModels.OperationSnapshot actual) {
        if (expected.deprecated()) {
            if (!actual.deprecated()) {
                violations.add(key + " deprecation metadata removed");
            }
            if (!str(expected.replacement()).equals(str(actual.replacement()))
                    || !str(expected.removalVersion()).equals(str(actual.removalVersion()))) {
                violations.add(key + " deprecation replacement/removal version changed");
            }
        } else if (actual.deprecated()
                && (str(actual.replacement()).isBlank()
                || !futureMajor(actual.removalVersion()))) {
            violations.add(key + " deprecated without replacement and future-major removal version");
        }
        if (!actual.deprecated()
                && (!str(actual.replacement()).isBlank() || !str(actual.removalVersion()).isBlank())) {
            violations.add(key + " has deprecation metadata but deprecated=false");
        }
    }

    private static void compareSchema(List<String> violations,
                                      FreezeModels.SchemaSnapshot expected,
                                      FreezeModels.SchemaSnapshot actual) {
        if (!str(expected.type()).equals(str(actual.type()))
                || !str(expected.format()).equals(str(actual.format()))) {
            violations.add("schema " + expected.name() + " type/format changed");
        }
        if (!list(expected.composition()).equals(list(actual.composition()))) {
            violations.add("schema " + expected.name() + " composition changed");
        }
        compareAdditionalProperties(violations,
                "schema " + expected.name() + " additionalProperties",
                expected.additionalProperties(), actual.additionalProperties());
        for (FreezeModels.PropertySnapshot property : list(expected.properties())) {
            FreezeModels.PropertySnapshot live = list(actual.properties()).stream()
                    .filter(candidate -> candidate.name().equals(property.name()))
                    .findFirst().orElse(null);
            if (live == null) {
                violations.add("schema " + expected.name() + " property REMOVED: " + property.name());
                continue;
            }
            if (!str(property.type()).equals(str(live.type()))
                    || !str(property.format()).equals(str(live.format()))
                    || !str(property.schemaRef()).equals(str(live.schemaRef()))
                    || !str(property.itemsType()).equals(str(live.itemsType()))
                    || !str(property.itemsRef()).equals(str(live.itemsRef()))) {
                violations.add("schema " + expected.name() + " property changed: " + property.name());
            }
            compareAdditionalProperties(violations,
                    "schema " + expected.name() + " property " + property.name() + " additionalProperties",
                    property.additionalProperties(), live.additionalProperties());
            if (enumNarrowed(property.enumValues(), live.enumValues())) {
                violations.add("schema " + expected.name() + " property enum narrowed: " + property.name());
            }
            if (property.nullable() && !live.nullable()) {
                violations.add("schema " + expected.name() + " property nullable->non-null: " + property.name());
            }
        }
        for (String required : list(actual.required())) {
            if (!list(expected.required()).contains(required)) {
                violations.add("schema " + expected.name()
                        + " property became/newly REQUIRED: " + required);
            }
        }
    }

    /**
     * Additive-only compatibility for the frozen additionalProperties contract (component schema and
     * property level). Open (unset/true) and typed-map shapes may only relax; narrowing transitions
     * (open/true -> false, open -> typed, typed -> false) are breaking. For two typed contracts the
     * full normalized value schema is compared recursively so type/ref/format/constraint mutation,
     * enum narrowing, required additions and property removal are caught, while genuine safe
     * relaxations (enum widening, nullable relaxation, additive optional properties) pass. Safe
     * openness relaxations (false -> true/unset, typed -> open) and unchanged openness are allowed.
     */
    private static void compareAdditionalProperties(List<String> violations, String context,
                                                    String expected, String actual) {
        int expectedKind = additionalPropertiesKind(expected);
        int actualKind = additionalPropertiesKind(actual);
        final int OPEN = 0, CLOSED = 1, TYPED = 2;
        if (expectedKind == OPEN && actualKind == CLOSED) {
            violations.add(context + " open->closed (breaking): "
                    + str(expected) + " -> " + str(actual));
        } else if (expectedKind == OPEN && actualKind == TYPED) {
            violations.add(context + " open->typed (narrowed): "
                    + str(expected) + " -> " + str(actual));
        } else if (expectedKind == TYPED && actualKind == CLOSED) {
            violations.add(context + " typed->closed (breaking): "
                    + str(expected) + " -> " + str(actual));
        } else if (expectedKind == TYPED && actualKind == TYPED) {
            compareTypedAdditionalProperties(violations, context, expected, actual);
        }
        // OPEN<->OPEN, CLOSED->OPEN, CLOSED->TYPED, TYPED->OPEN: safe relaxation or unchanged.
    }

    /**
     * Compare two typed (object-valued) additionalProperties contracts by their full normalized
     * value-schema shape, reusing the recursive {@link #compareSchemaNode} comparator rather than
     * opaque string equality. A non-canonical value (e.g. a legacy {@code type:}/{@code ref:}
     * snapshot without a {@code schema:<json>} body) falls back to exact equality so a difference is
     * never silently accepted.
     */
    private static void compareTypedAdditionalProperties(List<String> violations, String context,
                                                         String expected, String actual) {
        com.fasterxml.jackson.databind.JsonNode frozen = readSchemaContract(expected);
        com.fasterxml.jackson.databind.JsonNode live = readSchemaContract(actual);
        if (frozen != null && live != null) {
            compareSchemaNode(violations, context, frozen, live);
            return;
        }
        if (!str(expected).equals(str(actual))) {
            violations.add(context + " schema changed: " + expected + " -> " + actual);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode readSchemaContract(String value) {
        if (value == null || !value.startsWith("schema:")) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(value.substring("schema:".length()));
            return node.isObject() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static int additionalPropertiesKind(String value) {
        if (value == null || value.isEmpty() || "true".equals(value)) {
            return 0; // OPEN (unset or explicitly true)
        }
        if ("false".equals(value)) {
            return 1; // CLOSED
        }
        return 2; // TYPED (schema:<normalized json>)
    }

    /** Adding a finite enum where none existed, or removing a frozen value, narrows input. */
    private static boolean enumNarrowed(List<String> frozen, List<String> live) {
        if (frozen == null) {
            return live != null;
        }
        if (live == null) {
            return false;
        }
        return !live.containsAll(frozen);
    }

    private static boolean futureMajor(String value) {
        return str(value).matches("[vV]?(?:[2-9]|[1-9][0-9]+)(?:\\..*)?");
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }
}
