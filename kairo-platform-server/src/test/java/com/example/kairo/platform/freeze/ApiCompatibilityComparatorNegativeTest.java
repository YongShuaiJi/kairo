package com.example.kairo.platform.freeze;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Mutation-style proof that every declared breaking-change class is actually rejected. */
class ApiCompatibilityComparatorNegativeTest {

    private static final FreezeModels.ParamSnapshot OPTIONAL_QUERY = new FreezeModels.ParamSnapshot(
            "mode", "query", false, "string", null, true, null, null,
            "{\"type\":\"string\"}");
    private static final FreezeModels.PropertySnapshot STATUS = new FreezeModels.PropertySnapshot(
            "status", "string", null, null, true, null, null, null, null);
    private static final FreezeModels.ResponseSnapshot OK = new FreezeModels.ResponseSnapshot(
            "200", "Widget", List.of("application/json"));
    private static final FreezeModels.SchemaSnapshot WIDGET = new FreezeModels.SchemaSnapshot(
            "Widget", "object", null, List.of(), List.of(STATUS), List.of(), null);
    private static final FreezeModels.SecuritySchemeSnapshot BEARER =
            new FreezeModels.SecuritySchemeSnapshot("BearerAuth", "http", "bearer", "JWT");
    private static final FreezeModels.OperationSnapshot OPERATION = operation(
            false, "", "", List.of(), List.of(OPTIONAL_QUERY), false, null,
            List.of(), List.of(OK));
    private static final FreezeModels.NormalizedApi BASELINE = api(OPERATION, WIDGET, BEARER);

    @Test
    void additiveChangesRemainCompatible() {
        FreezeModels.ParamSnapshot optionalHeader = new FreezeModels.ParamSnapshot(
                "X-Optional", "header", false, "string", null, true, null, null,
                "{\"type\":\"string\"}");
        FreezeModels.PropertySnapshot detail = new FreezeModels.PropertySnapshot(
                "detail", "string", null, null, true, null, null, null, null);
        FreezeModels.OperationSnapshot additiveOperation = operation(
                false, "", "", List.of(), List.of(OPTIONAL_QUERY, optionalHeader), false, null,
                List.of(), List.of(OK, new FreezeModels.ResponseSnapshot(
                        "202", "Widget", List.of("application/json"))));
        FreezeModels.SchemaSnapshot additiveSchema = new FreezeModels.SchemaSnapshot(
                "Widget", "object", null, List.of(), List.of(STATUS, detail), List.of(), null);

        assertThat(ApiCompatibilityComparator.compare(BASELINE,
                api(additiveOperation, additiveSchema, BEARER))).isEmpty();
    }

    @Test
    void operationRemovalAndAuthenticationStrengtheningAreRejected() {
        assertViolation(new FreezeModels.NormalizedApi(List.of(), List.of(WIDGET), List.of(BEARER)),
                "OPERATION REMOVED");
        assertViolation(withOperation(operation(false, "", "", List.of("BearerAuth[]"),
                List.of(OPTIONAL_QUERY), false, null, List.of(), List.of(OK))),
                "authentication requirement changed");
        FreezeModels.NormalizedApi securedBaseline = api(operation(false, "", "",
                List.of("BearerAuth[]"), List.of(OPTIONAL_QUERY), false, null,
                List.of(), List.of(OK)), WIDGET, BEARER);
        assertThat(ApiCompatibilityComparator.compare(securedBaseline, BASELINE))
                .anyMatch(v -> v.contains("authentication requirement changed"));
        FreezeModels.OperationSnapshot permissionRaised = new FreezeModels.OperationSnapshot(
                "get", "/api/v1/widgets", false, "", "", "ADMIN", List.of(),
                List.of(OPTIONAL_QUERY), false, null, List.of(), List.of(OK));
        assertViolation(withOperation(permissionRaised), "authorization requirement changed");
    }

    @Test
    void requiredBodyRemovedContentAndResponseChangesAreRejected() {
        assertViolation(withOperation(operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), true, "WidgetInput",
                List.of("application/json"), List.of(OK))), "requestBody became REQUIRED");
        assertViolation(withOperation(operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, null, List.of(), List.of())),
                "response status REMOVED");
        FreezeModels.ResponseSnapshot noJson = new FreezeModels.ResponseSnapshot("200", "Widget", List.of());
        assertViolation(withOperation(operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, null, List.of(), List.of(noJson))),
                "content type removed");

        FreezeModels.OperationSnapshot bodyBaseline = operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, "WidgetInput", List.of("application/json"), List.of(OK));
        FreezeModels.OperationSnapshot bodyWithoutJson = operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, "WidgetInput", List.of(), List.of(OK));
        FreezeModels.NormalizedApi frozen = api(bodyBaseline, WIDGET, BEARER);
        assertThat(ApiCompatibilityComparator.compare(frozen,
                api(bodyWithoutJson, WIDGET, BEARER)))
                .anyMatch(violation -> violation.contains("request content type removed"));
    }

    @Test
    void inlineRequestAndResponseSchemaMutationsAreRejectedWhileOptionalFieldsAreAllowed() {
        String frozen = "{\"application/json\":{\"type\":\"object\","
                + "\"required\":[\"status\"],\"properties\":{"
                + "\"status\":{\"type\":\"string\",\"enum\":[\"ACTIVE\",\"DISABLED\"]},"
                + "\"items\":{\"type\":\"array\",\"items\":{\"$ref\":\"EntryV1\"}}}}}";
        String removed = "{\"application/json\":{\"type\":\"object\","
                + "\"required\":[\"status\"],\"properties\":{"
                + "\"status\":{\"type\":\"string\",\"enum\":[\"ACTIVE\",\"DISABLED\"]}}}}";
        String narrowed = "{\"application/json\":{\"type\":\"object\","
                + "\"required\":[\"status\",\"items\"],\"properties\":{"
                + "\"status\":{\"type\":\"string\",\"enum\":[\"ACTIVE\"]},"
                + "\"items\":{\"type\":\"array\",\"items\":{\"$ref\":\"EntryV2\"}}}}}";
        String additive = "{\"application/json\":{\"type\":\"object\","
                + "\"required\":[\"status\"],\"properties\":{"
                + "\"detail\":{\"type\":\"string\"},"
                + "\"status\":{\"type\":\"string\",\"enum\":[\"ACTIVE\",\"DISABLED\",\"PAUSED\"]},"
                + "\"items\":{\"type\":\"array\",\"items\":{\"$ref\":\"EntryV1\"}}}}}";

        FreezeModels.OperationSnapshot frozenOperation = operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, frozen, List.of("application/json"),
                List.of(new FreezeModels.ResponseSnapshot("200", frozen, List.of("application/json"))));
        FreezeModels.NormalizedApi frozenApi = api(frozenOperation, WIDGET, BEARER);

        FreezeModels.OperationSnapshot removedOperation = operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, removed, List.of("application/json"),
                List.of(new FreezeModels.ResponseSnapshot("200", removed, List.of("application/json"))));
        assertThat(ApiCompatibilityComparator.compare(frozenApi,
                api(removedOperation, WIDGET, BEARER)))
                .anyMatch(v -> v.contains("property REMOVED: items"));

        FreezeModels.OperationSnapshot narrowedOperation = operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, narrowed, List.of("application/json"),
                List.of(new FreezeModels.ResponseSnapshot("200", narrowed, List.of("application/json"))));
        List<String> narrowedViolations = ApiCompatibilityComparator.compare(frozenApi,
                api(narrowedOperation, WIDGET, BEARER));
        assertThat(narrowedViolations).anyMatch(v -> v.contains("enum narrowed"));
        assertThat(narrowedViolations).anyMatch(v -> v.contains("newly REQUIRED: items"));
        assertThat(narrowedViolations).anyMatch(v -> v.contains("$ref changed"));

        FreezeModels.OperationSnapshot additiveOperation = operation(false, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, additive, List.of("application/json"),
                List.of(new FreezeModels.ResponseSnapshot("200", additive, List.of("application/json"))));
        assertThat(ApiCompatibilityComparator.compare(frozenApi,
                api(additiveOperation, WIDGET, BEARER))).isEmpty();
    }

    @Test
    void parameterRemovalRequirementTypeNullableAndEnumNarrowingAreRejected() {
        assertViolation(withOperation(operation(false, "", "", List.of(), List.of(),
                false, null, List.of(), List.of(OK))), "parameter REMOVED");
        assertViolation(withParameter(new FreezeModels.ParamSnapshot(
                "mode", "query", true, "string", null, true, null, null,
                "{\"type\":\"string\"}")),
                "parameter became REQUIRED");
        assertViolation(withParameter(new FreezeModels.ParamSnapshot(
                "mode", "query", false, "integer", null, true, null, null,
                "{\"type\":\"integer\"}")),
                "parameter schema changed");
        assertViolation(withParameter(new FreezeModels.ParamSnapshot(
                "mode", "query", false, "string", null, false, null, null,
                "{\"type\":\"string\"}")),
                "nullable->non-null");
        assertViolation(withParameter(new FreezeModels.ParamSnapshot(
                "mode", "query", false, "string", null, true, null, List.of("SAFE"),
                "{\"type\":\"string\",\"enum\":[\"SAFE\"]}")),
                "parameter enum narrowed");
        assertViolation(withParameter(new FreezeModels.ParamSnapshot(
                "mode", "query", false, "string", null, true, null, null,
                "{\"type\":\"string\",\"default\":\"STRICT\"}")),
                "default changed");
    }

    @Test
    void schemaRemovalPropertyChangesRequirementsAndEnumNarrowingAreRejected() {
        assertViolation(new FreezeModels.NormalizedApi(List.of(OPERATION), List.of(), List.of(BEARER)),
                "SCHEMA REMOVED");
        assertViolation(withSchema(new FreezeModels.SchemaSnapshot(
                "Widget", "object", null, List.of(), List.of(), List.of(), null)),
                "property REMOVED");
        assertViolation(withSchema(schemaWith(new FreezeModels.PropertySnapshot(
                "status", "integer", null, null, true, null, null, null, null), List.of())),
                "property changed");
        assertViolation(withSchema(schemaWith(new FreezeModels.PropertySnapshot(
                "status", "string", null, null, false, null, null, null, null), List.of())),
                "nullable->non-null");
        assertViolation(withSchema(schemaWith(new FreezeModels.PropertySnapshot(
                "status", "string", null, null, true, List.of("ACTIVE"), null, null, null), List.of())),
                "property enum narrowed");
        assertViolation(withSchema(schemaWith(STATUS, List.of("status"))),
                "property became/newly REQUIRED");

        FreezeModels.PropertySnapshot requiredAddition = new FreezeModels.PropertySnapshot(
                "newField", "string", null, null, true, null, null, null, null);
        assertViolation(withSchema(new FreezeModels.SchemaSnapshot(
                "Widget", "object", null, List.of("newField"),
                List.of(STATUS, requiredAddition), List.of(), null)), "property became/newly REQUIRED");

        FreezeModels.PropertySnapshot frozenRef = new FreezeModels.PropertySnapshot(
                "status", "$ref", null, "StatusV1", true, null, null, null, null);
        FreezeModels.PropertySnapshot changedRef = new FreezeModels.PropertySnapshot(
                "status", "$ref", null, "StatusV2", true, null, null, null, null);
        FreezeModels.NormalizedApi refBaseline = api(OPERATION,
                schemaWith(frozenRef, List.of()), BEARER);
        assertThat(ApiCompatibilityComparator.compare(refBaseline,
                api(OPERATION, schemaWith(changedRef, List.of()), BEARER)))
                .anyMatch(violation -> violation.contains("property changed"));
    }

    @Test
    void invalidDeprecationAndSecuritySchemeMutationAreRejected() {
        assertViolation(withOperation(operation(true, "", "", List.of(),
                List.of(OPTIONAL_QUERY), false, null, List.of(), List.of(OK))),
                "deprecated without replacement");
        FreezeModels.OperationSnapshot validDeprecation = operation(true,
                "/api/v2/widgets", "V2", List.of(), List.of(OPTIONAL_QUERY),
                false, null, List.of(), List.of(OK));
        assertThat(ApiCompatibilityComparator.compare(BASELINE, withOperation(validDeprecation))).isEmpty();
        FreezeModels.SecuritySchemeSnapshot changed =
                new FreezeModels.SecuritySchemeSnapshot("BearerAuth", "apiKey", null, null);
        assertViolation(api(OPERATION, WIDGET, changed), "SECURITY SCHEME CHANGED");
    }

    private static FreezeModels.NormalizedApi withParameter(FreezeModels.ParamSnapshot parameter) {
        return withOperation(operation(false, "", "", List.of(), List.of(parameter),
                false, null, List.of(), List.of(OK)));
    }

    private static FreezeModels.NormalizedApi withOperation(FreezeModels.OperationSnapshot operation) {
        return api(operation, WIDGET, BEARER);
    }

    private static FreezeModels.NormalizedApi withSchema(FreezeModels.SchemaSnapshot schema) {
        return api(OPERATION, schema, BEARER);
    }

    private static FreezeModels.SchemaSnapshot schemaWith(
            FreezeModels.PropertySnapshot property, List<String> required) {
        return new FreezeModels.SchemaSnapshot(
                "Widget", "object", null, required, List.of(property), List.of(), null);
    }

    private static FreezeModels.OperationSnapshot operation(
            boolean deprecated, String replacement, String removalVersion,
            List<String> security, List<FreezeModels.ParamSnapshot> parameters,
            boolean requestRequired, String requestSchema, List<String> requestContentTypes,
            List<FreezeModels.ResponseSnapshot> responses) {
        return new FreezeModels.OperationSnapshot("get", "/api/v1/widgets", deprecated,
                replacement, removalVersion, "AUTHENTICATED", security, parameters, requestRequired, requestSchema,
                requestContentTypes, responses);
    }

    private static FreezeModels.NormalizedApi api(FreezeModels.OperationSnapshot operation,
                                                   FreezeModels.SchemaSnapshot schema,
                                                   FreezeModels.SecuritySchemeSnapshot scheme) {
        return new FreezeModels.NormalizedApi(List.of(operation), List.of(schema), List.of(scheme));
    }

    private static void assertViolation(FreezeModels.NormalizedApi current, String expectedText) {
        List<String> violations = new ArrayList<>(
                ApiCompatibilityComparator.compare(BASELINE, current));
        assertThat(violations)
                .as("mutation must be rejected: " + expectedText)
                .anyMatch(violation -> violation.contains(expectedText));
    }
}
