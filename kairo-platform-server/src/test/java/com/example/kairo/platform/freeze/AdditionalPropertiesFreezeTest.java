package com.example.kairo.platform.freeze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N1 mutation proof: component/schema-level and property-level {@code additionalProperties} is
 * frozen with its openness/value-shape, and {@link ApiCompatibilityComparator} enforces additive-only
 * compatibility. Narrowing (open/unset or true -> false, open -> typed, typed -> false) and typed
 * shape/ref mutation are rejected; safe relaxations (false -> true/unset, typed -> open) and
 * unchanged contracts pass. The same semantics are asserted for the recursive body/parameter
 * comparator so a request/response body cannot silently narrow an open map.
 */
class AdditionalPropertiesFreezeTest {

    private static final FreezeModels.SecuritySchemeSnapshot BEARER =
            new FreezeModels.SecuritySchemeSnapshot("BearerAuth", "http", "bearer", "JWT");
    private static final FreezeModels.OperationSnapshot OPERATION =
            new FreezeModels.OperationSnapshot("get", "/api/v1/maps", false, "", "",
                    "AUTHENTICATED", List.of(), List.of(), false, null, List.of(), List.of());

    // ---------------- schema (component) level ----------------

    @Test
    void schemaAdditionalPropertiesNarrowingIsRejected() {
        // open (unset) -> closed
        assertRejected(schema(null), schema("false"), "additionalProperties open->closed");
        // explicitly open -> closed
        assertRejected(schema("true"), schema("false"), "additionalProperties open->closed");
        // open -> typed (narrowed: was any extra, now must match schema)
        assertRejected(schema(null), schema("type:object"), "additionalProperties open->typed");
        assertRejected(schema("true"), schema("ref:MapEntry"), "additionalProperties open->typed");
        // typed -> closed
        assertRejected(schema("type:object"), schema("false"), "additionalProperties typed->closed");
        assertRejected(schema("ref:MapEntry"), schema("false"), "additionalProperties typed->closed");
    }

    @Test
    void schemaAdditionalPropertiesShapeAndRefMutationIsRejected() {
        // type shape mutation
        assertRejected(schema("type:object"), schema("type:string"),
                "additionalProperties schema changed: type:object -> type:string");
        // ref mutation
        assertRejected(schema("ref:MapEntryV1"), schema("ref:MapEntryV2"),
                "additionalProperties schema changed: ref:MapEntryV1 -> ref:MapEntryV2");
        // ref -> type is a shape change
        assertRejected(schema("ref:MapEntry"), schema("type:object"),
                "additionalProperties schema changed");
    }

    @Test
    void schemaAdditionalPropertiesSafeRelaxationsAndUnchangedPass() {
        // closed -> open (relaxing)
        assertCompatible(schema("false"), schema("true"));
        assertCompatible(schema("false"), schema(null));
        // typed -> open (relaxing)
        assertCompatible(schema("type:object"), schema(null));
        assertCompatible(schema("type:object"), schema("true"));
        assertCompatible(schema("ref:MapEntry"), schema("true"));
        // closed -> typed (relaxing: was nothing, now accepts matching schema)
        assertCompatible(schema("false"), schema("type:object"));
        // open <-> open (semantically identical)
        assertCompatible(schema(null), schema("true"));
        assertCompatible(schema("true"), schema(null));
        // unchanged
        assertCompatible(schema("type:object"), schema("type:object"));
        assertCompatible(schema("ref:MapEntry"), schema("ref:MapEntry"));
        assertCompatible(schema("false"), schema("false"));
        assertCompatible(schema(null), schema(null));
    }

    // ---------------- property level ----------------

    @Test
    void propertyAdditionalPropertiesNarrowingAndMutationAreRejected() {
        FreezeModels.PropertySnapshot openProp = prop("labels", null);
        FreezeModels.PropertySnapshot closedProp = prop("labels", "false");
        FreezeModels.PropertySnapshot typedObject = prop("labels", "type:object");
        FreezeModels.PropertySnapshot typedString = prop("labels", "type:string");
        FreezeModels.PropertySnapshot refV1 = prop("labels", "ref:MapEntryV1");
        FreezeModels.PropertySnapshot refV2 = prop("labels", "ref:MapEntryV2");

        assertRejected(schemaWith(openProp), schemaWith(closedProp), "additionalProperties open->closed");
        assertRejected(schemaWith(prop("labels", "true")), schemaWith(closedProp),
                "additionalProperties open->closed");
        assertRejected(schemaWith(openProp), schemaWith(typedObject), "additionalProperties open->typed");
        assertRejected(schemaWith(typedObject), schemaWith(closedProp),
                "additionalProperties typed->closed");
        assertRejected(schemaWith(typedObject), schemaWith(typedString),
                "additionalProperties schema changed: type:object -> type:string");
        assertRejected(schemaWith(refV1), schemaWith(refV2),
                "additionalProperties schema changed: ref:MapEntryV1 -> ref:MapEntryV2");
    }

    @Test
    void propertyAdditionalPropertiesSafeRelaxationsPass() {
        assertCompatible(schemaWith(prop("labels", "false")), schemaWith(prop("labels", "true")));
        assertCompatible(schemaWith(prop("labels", "false")), schemaWith(prop("labels", null)));
        assertCompatible(schemaWith(prop("labels", "type:object")), schemaWith(prop("labels", null)));
        assertCompatible(schemaWith(prop("labels", "type:object")),
                schemaWith(prop("labels", "type:object")));
    }

    // ---------------- recursive body/parameter comparator ----------------

    @Test
    void bodyAdditionalPropertiesNarrowingAndShapeMutationAreRejected() {
        String open = body("{\"type\":\"object\",\"additionalProperties\":true}");
        String typedObject = body("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"object\"}}");
        String typedString = body("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}");
        String closed = body("{\"type\":\"object\",\"additionalProperties\":false}");

        // open -> closed and open -> typed are narrowing
        assertBodyRejected(open, closed, "additionalProperties open->closed");
        assertBodyRejected(open, typedObject, "additionalProperties open->typed");
        // typed -> closed is narrowing
        assertBodyRejected(typedObject, closed, "additionalProperties typed->closed");
        // typed value-schema shape mutation (type changed) is caught by the recursive node compare
        assertBodyRejected(typedObject, typedString, "type changed");
    }

    @Test
    void bodyAdditionalPropertiesSafeRelaxationsAreAllowed() {
        // closed -> open and typed -> open are relaxing: must NOT be rejected (the bug being fixed)
        String open = body("{\"type\":\"object\",\"additionalProperties\":true}");
        String closed = body("{\"type\":\"object\",\"additionalProperties\":false}");
        String typedObject = body("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"object\"}}");
        String unset = body("{\"type\":\"object\"}");
        // An empty additionalProperties schema ({} -- no $ref/type) is OPEN (any value), so
        // typed -> empty is a safe relaxation. swagger-core 2.2.47 (via springdoc 2.8.17 for
        // Spring Boot 3.5.16) renders Object-typed map values this way; it must not be flagged.
        String empty = body("{\"type\":\"object\",\"additionalProperties\":{}}");

        assertThat(ApiCompatibilityComparator.compare(apiWithBody(closed), apiWithBody(open)))
                .as("closed -> open is a safe relaxation").isEmpty();
        assertThat(ApiCompatibilityComparator.compare(apiWithBody(closed), apiWithBody(unset)))
                .as("closed -> unset is a safe relaxation").isEmpty();
        assertThat(ApiCompatibilityComparator.compare(apiWithBody(typedObject), apiWithBody(open)))
                .as("typed -> open is a safe relaxation").isEmpty();
        assertThat(ApiCompatibilityComparator.compare(apiWithBody(typedObject), apiWithBody(empty)))
                .as("typed -> empty schema {} (open) is a safe relaxation").isEmpty();
        assertThat(ApiCompatibilityComparator.compare(apiWithBody(open), apiWithBody(unset)))
                .as("open <-> unset are semantically identical").isEmpty();
        assertThat(ApiCompatibilityComparator.compare(apiWithBody(empty), apiWithBody(unset)))
                .as("empty {} <-> unset are semantically identical (both open)").isEmpty();
        assertThat(ApiCompatibilityComparator.compare(apiWithBody(typedObject), apiWithBody(typedObject)))
                .as("unchanged typed map is compatible").isEmpty();
    }

    @Test
    void parameterAdditionalPropertiesNarrowingIsRejectedWhileRelaxationPasses() {
        // Parameter schemas carry additionalProperties through the recursive comparator too.
        String open = "{\"type\":\"object\",\"additionalProperties\":true}";
        String closed = "{\"type\":\"object\",\"additionalProperties\":false}";
        String typedObject = "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"object\"}}";
        String typedString = "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}";

        assertThat(ApiCompatibilityComparator.compare(apiWithParam(open), apiWithParam(closed)))
                .anyMatch(v -> v.contains("additionalProperties open->closed"));
        assertThat(ApiCompatibilityComparator.compare(apiWithParam(typedObject), apiWithParam(typedString)))
                .anyMatch(v -> v.contains("type changed"));
        assertThat(ApiCompatibilityComparator.compare(apiWithParam(closed), apiWithParam(open)))
                .as("closed -> open is a safe relaxation").isEmpty();
    }

    // ---------------- full-shape additionalProperties (canonical schema:<json> form) ----------------
    // The canonical collector form for every object-valued additionalProperties is
    // "schema:<normalized json>" (see FreezeCollectors). These prove the comparator recurses over
    // that full value shape so a mutation invisible to a bare "type:<type>" -- format change, enum
    // narrowing, nested-property removal, constraint tightening -- is now caught, while genuine
    // safe relaxations (enum widening, nullable relaxation, additive optional properties) pass.

    @Test
    void schemaLevelTypedAdditionalPropertiesRejectsFormatRefTypeAndConstraintMutation() {
        // format mutation -- the headline bug: {type:string,format:uuid} -> {type:string,format:date}
        assertRejected(schema(typed("{\"type\":\"string\",\"format\":\"uuid\"}")),
                schema(typed("{\"type\":\"string\",\"format\":\"date\"}")),
                "additionalProperties format changed");
        // identical full shape is compatible
        assertCompatible(schema(typed("{\"type\":\"string\",\"format\":\"uuid\"}")),
                schema(typed("{\"type\":\"string\",\"format\":\"uuid\"}")));
        // adding a format where none existed narrows input
        assertRejected(schema(typed("{\"type\":\"string\"}")),
                schema(typed("{\"type\":\"string\",\"format\":\"uuid\"}")),
                "additionalProperties format changed");
        // $ref target mutation
        assertRejected(schema(typed("{\"$ref\":\"MapEntryV1\"}")),
                schema(typed("{\"$ref\":\"MapEntryV2\"}")),
                "additionalProperties $ref changed");
        // type mutation
        assertRejected(schema(typed("{\"type\":\"object\"}")),
                schema(typed("{\"type\":\"string\"}")),
                "additionalProperties type changed");
        // constraint tightening (minLength 5 -> 10 rejects shorter values)
        assertRejected(schema(typed("{\"type\":\"string\",\"minLength\":5}")),
                schema(typed("{\"type\":\"string\",\"minLength\":10}")),
                "additionalProperties minLength changed");
    }

    @Test
    void schemaLevelTypedAdditionalPropertiesEnumIsDirectional() {
        // narrowing (a frozen value is dropped) is breaking
        assertRejected(schema(typed("{\"type\":\"string\",\"enum\":[\"ACTIVE\",\"DISABLED\"]}")),
                schema(typed("{\"type\":\"string\",\"enum\":[\"ACTIVE\"]}")),
                "additionalProperties enum narrowed");
        // widening (a new value is added) is a safe relaxation
        assertCompatible(schema(typed("{\"type\":\"string\",\"enum\":[\"ACTIVE\"]}")),
                schema(typed("{\"type\":\"string\",\"enum\":[\"ACTIVE\",\"DISABLED\"]}")));
        // introducing a finite enum where the value was unconstrained narrows input
        assertRejected(schema(typed("{\"type\":\"string\"}")),
                schema(typed("{\"type\":\"string\",\"enum\":[\"ACTIVE\"]}")),
                "additionalProperties enum narrowed");
    }

    @Test
    void schemaLevelTypedAdditionalPropertiesNestedPropertiesAreDirectional() {
        String idOnly = typed("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        String idAndName = typed("{\"type\":\"object\",\"properties\":{"
                + "\"id\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}");
        // removing a frozen nested property is breaking
        assertRejected(schema(idAndName), schema(idOnly),
                "additionalProperties property REMOVED: name");
        // adding an optional nested property is a safe, additive relaxation
        assertCompatible(schema(idOnly), schema(idAndName));
        // making a property newly required is breaking
        assertRejected(schema(idOnly),
                schema(typed("{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{"
                        + "\"id\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}")),
                "additionalProperties property became/newly REQUIRED: name");
    }

    @Test
    void schemaLevelTypedAdditionalPropertiesNullableIsDirectional() {
        // nullable relaxation (non-null -> nullable) is safe
        assertCompatible(schema(typed("{\"type\":\"string\",\"nullable\":false}")),
                schema(typed("{\"type\":\"string\",\"nullable\":true}")));
        // nullable -> non-null is breaking
        assertRejected(schema(typed("{\"type\":\"string\",\"nullable\":true}")),
                schema(typed("{\"type\":\"string\",\"nullable\":false}")),
                "additionalProperties nullable->non-null");
    }

    @Test
    void propertyLevelTypedAdditionalPropertiesEnforcesFullShape() {
        // The property-level String contract must recurse over the value shape too, not just
        // compare the bare type, so a format mutation on a map-typed property is rejected.
        FreezeModels.PropertySnapshot uuid = prop("labels", typed("{\"type\":\"string\",\"format\":\"uuid\"}"));
        FreezeModels.PropertySnapshot date = prop("labels", typed("{\"type\":\"string\",\"format\":\"date\"}"));
        assertRejected(schemaWith(uuid), schemaWith(date),
                "additionalProperties format changed");
        assertCompatible(schemaWith(uuid), schemaWith(uuid));
    }

    @Test
    void bodyLevelTypedAdditionalPropertiesRejectsFormatMutation() {
        // The recursive body/parameter comparator already normalized nested additionalProperties as
        // nodes; this confirms a format mutation inside a request-body map value is still caught.
        String uuid = body("{\"type\":\"object\",\"additionalProperties\":"
                + "{\"type\":\"string\",\"format\":\"uuid\"}}");
        String date = body("{\"type\":\"object\",\"additionalProperties\":"
                + "{\"type\":\"string\",\"format\":\"date\"}}");
        assertBodyRejected(uuid, date, "additionalProperties format changed");
        assertThat(ApiCompatibilityComparator.compare(apiWithBody(uuid), apiWithBody(uuid)))
                .as("unchanged full-shape map value is compatible").isEmpty();
    }

    @Test
    void legacyTypedAdditionalPropertiesSnapshotNeverSilentlyAcceptsADifference() {
        // Non-canonical typed snapshots (legacy "type:"/"ref:" literals without a schema:<json>
        // body) fall back to exact equality so any difference is still flagged.
        assertRejected(schema("type:object"), schema("type:string"),
                "additionalProperties schema changed: type:object -> type:string");
        assertRejected(schema("ref:MapEntryV1"), schema("ref:MapEntryV2"),
                "additionalProperties schema changed: ref:MapEntryV1 -> ref:MapEntryV2");
        assertCompatible(schema("type:object"), schema("type:object"));
        assertCompatible(schema("ref:MapEntry"), schema("ref:MapEntry"));
    }

    // ---------------- helpers ----------------

    private static FreezeModels.SchemaSnapshot schema(String additionalProperties) {
        return new FreezeModels.SchemaSnapshot("Map", "object", null, List.of(), List.of(),
                List.of(), additionalProperties);
    }

    /** Wrap a normalized value-schema JSON as the canonical {@code "schema:<json>"} contract. */
    private static String typed(String normalizedJson) {
        return "schema:" + normalizedJson;
    }

    private static FreezeModels.SchemaSnapshot schemaWith(FreezeModels.PropertySnapshot property) {
        return new FreezeModels.SchemaSnapshot("Map", "object", null, List.of(), List.of(property),
                List.of(), null);
    }

    private static FreezeModels.PropertySnapshot prop(String name, String additionalProperties) {
        return new FreezeModels.PropertySnapshot(name, "object", null, null, true, null,
                null, null, additionalProperties);
    }

    private static String body(String schemaContract) {
        return "{\"application/json\":" + schemaContract + "}";
    }

    private static FreezeModels.OperationSnapshot operationWithBody(String requestSchema) {
        return new FreezeModels.OperationSnapshot("post", "/api/v1/maps", false, "", "",
                "AUTHENTICATED", List.of(), List.of(), false, requestSchema,
                List.of("application/json"), List.of());
    }

    private static FreezeModels.OperationSnapshot operationWithParam(String schemaContract) {
        FreezeModels.ParamSnapshot param = new FreezeModels.ParamSnapshot("filter", "query",
                false, "object", null, true, null, null, schemaContract);
        return new FreezeModels.OperationSnapshot("get", "/api/v1/maps", false, "", "",
                "AUTHENTICATED", List.of(), List.of(param), false, null, List.of(), List.of());
    }

    private static FreezeModels.NormalizedApi apiWithBody(String requestSchema) {
        return new FreezeModels.NormalizedApi(List.of(operationWithBody(requestSchema)),
                List.of(schema(null)), List.of(BEARER));
    }

    private static FreezeModels.NormalizedApi apiWithParam(String schemaContract) {
        return new FreezeModels.NormalizedApi(List.of(operationWithParam(schemaContract)),
                List.of(schema(null)), List.of(BEARER));
    }

    private static FreezeModels.NormalizedApi apiWithSchema(FreezeModels.SchemaSnapshot schema) {
        return new FreezeModels.NormalizedApi(List.of(OPERATION), List.of(schema), List.of(BEARER));
    }

    private static void assertRejected(FreezeModels.SchemaSnapshot baseline,
                                       FreezeModels.SchemaSnapshot current, String expectedText) {
        assertThat(ApiCompatibilityComparator.compare(apiWithSchema(baseline), apiWithSchema(current)))
                .as("mutation must be rejected: " + expectedText)
                .anyMatch(v -> v.contains(expectedText));
    }

    private static void assertCompatible(FreezeModels.SchemaSnapshot baseline,
                                        FreezeModels.SchemaSnapshot current) {
        List<String> violations = ApiCompatibilityComparator.compare(
                apiWithSchema(baseline), apiWithSchema(current));
        assertThat(violations)
                .as("compatible change must not be rejected: " + baseline.additionalProperties()
                        + " -> " + current.additionalProperties())
                .isEmpty();
    }

    private static void assertBodyRejected(String baselineContract, String currentContract,
                                           String expectedText) {
        assertThat(ApiCompatibilityComparator.compare(apiWithBody(baselineContract),
                apiWithBody(currentContract)))
                .as("body mutation must be rejected: " + expectedText)
                .anyMatch(v -> v.contains(expectedText));
    }
}
