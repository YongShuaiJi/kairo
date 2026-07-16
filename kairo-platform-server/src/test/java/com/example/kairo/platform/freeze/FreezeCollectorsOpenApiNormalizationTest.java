package com.example.kairo.platform.freeze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FreezeCollectorsOpenApiNormalizationTest {

    @Test
    void normalizesInheritedSecurityContentTypesAndDeprecationMetadata() throws Exception {
        JsonNode document = new ObjectMapper().readTree("""
                {
                  "security": [{"bearerAuth": []}],
                  "paths": {
                    "/api/v1/widgets": {
                      "post": {
                        "deprecated": true,
                        "x-kairo-authorization": "RULE_MANAGE",
                        "x-kairo-replacement": "/api/v2/widgets",
                        "x-kairo-removal-version": "V2",
                        "requestBody": {
                          "required": false,
                          "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/Widget"}},
                            "application/cbor": {"schema": {"type": "object"}}
                          }
                        },
                        "responses": {
                          "200": {"content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/Widget"}},
                            "application/cbor": {"schema": {"type": "object"}}
                          }}
                        }
                      }
                    },
                    "/api/v1/public": {
                      "get": {"security": [], "responses": {"204": {}}}
                    }
                  },
                  "components": {
                    "securitySchemes": {
                      "bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "opaque"}
                    },
                    "schemas": {
                      "Widget": {"type": "object", "properties": {"id": {"type": "string"}}}
                    }
                  }
                }
                """);

        FreezeModels.NormalizedApi normalized = FreezeCollectors.normalizeOpenApi(document);
        FreezeModels.OperationSnapshot secured = normalized.operations().stream()
                .filter(operation -> operation.path().equals("/api/v1/widgets"))
                .findFirst().orElseThrow();
        assertThat(secured.security()).containsExactly("bearerAuth[]");
        assertThat(secured.authorization()).isEqualTo("RULE_MANAGE");
        assertThat(secured.deprecated()).isTrue();
        assertThat(secured.replacement()).isEqualTo("/api/v2/widgets");
        assertThat(secured.removalVersion()).isEqualTo("V2");
        assertThat(secured.requestContentTypes())
                .containsExactly("application/cbor", "application/json");
        assertThat(secured.responses().get(0).contentTypes())
                .containsExactly("application/cbor", "application/json");
        String bodyContract = "{\"application/cbor\":{\"type\":\"object\"},"
                + "\"application/json\":{\"$ref\":\"Widget\"}}";
        assertThat(secured.requestBodySchema()).isEqualTo(bodyContract);
        assertThat(secured.responses().get(0).schema()).isEqualTo(bodyContract);

        FreezeModels.OperationSnapshot publicOperation = normalized.operations().stream()
                .filter(operation -> operation.path().equals("/api/v1/public"))
                .findFirst().orElseThrow();
        assertThat(publicOperation.security()).isEmpty();
        assertThat(publicOperation.authorization()).isEqualTo("AUTHENTICATED");
    }

    @Test
    void normalizesAdditionalPropertiesAsFullValueSchemaWithStableLeafRefs() throws Exception {
        JsonNode document = new ObjectMapper().readTree("""
                {
                  "paths": {},
                  "components": {
                    "schemas": {
                      "OpenMap": {"type": "object"},
                      "ClosedMap": {"type": "object", "additionalProperties": false},
                      "ExplicitOpenMap": {"type": "object", "additionalProperties": true},
                      "PrimitiveMap": {"type": "object", "additionalProperties": {
                        "type": "string", "format": "uuid", "enum": ["b", "a"]}},
                      "RefMap": {"type": "object", "additionalProperties": {
                        "$ref": "#/components/schemas/OpenMap"}},
                      "NestedMap": {"type": "object", "additionalProperties": {
                        "type": "object", "required": ["id"], "properties": {
                          "id": {"type": "string"}, "name": {"type": "string"}}}}
                    }
                  }
                }
                """);

        FreezeModels.NormalizedApi normalized = FreezeCollectors.normalizeOpenApi(document);
        java.util.Map<String, FreezeModels.SchemaSnapshot> byName = new java.util.TreeMap<>();
        normalized.schemas().forEach(s -> byName.put(s.name(), s));

        // unset / boolean openness preserved verbatim
        assertThat(byName.get("OpenMap").additionalProperties()).isNull();
        assertThat(byName.get("ClosedMap").additionalProperties()).isEqualTo("false");
        assertThat(byName.get("ExplicitOpenMap").additionalProperties()).isEqualTo("true");

        // a primitive value schema keeps format + enum (sorted) + type -- not reduced to "type:string"
        assertThat(byName.get("PrimitiveMap").additionalProperties())
                .isEqualTo("schema:{\"type\":\"string\",\"format\":\"uuid\",\"enum\":[\"a\",\"b\"]}");

        // a $ref value schema is preserved as a stable leaf ref inside the full schema object
        assertThat(byName.get("RefMap").additionalProperties())
                .isEqualTo("schema:{\"$ref\":\"OpenMap\"}");

        // nested properties, required and items are preserved recursively
        assertThat(byName.get("NestedMap").additionalProperties())
                .isEqualTo("schema:{\"type\":\"object\",\"required\":[\"id\"],"
                        + "\"properties\":{\"id\":{\"type\":\"string\"},"
                        + "\"name\":{\"type\":\"string\"}}}");
    }
}
