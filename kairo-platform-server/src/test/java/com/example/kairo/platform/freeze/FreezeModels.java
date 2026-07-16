package com.example.kairo.platform.freeze;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Frozen-contract baseline models for the V1.7 M0 freeze gates (frozen plan &sect;3 / W0).
 * Serialized shape of the committed baselines under {@code src/test/resources/v1.7/}, generated
 * from the V1.6.0 baseline ({@code 113823b}).
 */
final class FreezeModels {

    private FreezeModels() {
    }

    // --- 3.2 database migrations ---
    record FrozenMigration(@JsonProperty("file") String file,
                           @JsonProperty("version") String version,
                           @JsonProperty("sha256") String sha256,
                           @JsonProperty("flywayChecksum") int flywayChecksum,
                           @JsonProperty("lines") int lines) {
    }

    record FrozenMigrations(@JsonProperty("migrations") List<FrozenMigration> migrations) {
    }

    // --- 3.3 configuration (compare every field) ---
    record ConfigKey(@JsonProperty("key") String key,
                     @JsonProperty("component") String component,
                     @JsonProperty("type") String type,
                     @JsonProperty("defaultValue") String defaultValue,
                     @JsonProperty("sensitive") boolean sensitive,
                     @JsonProperty("defaultPresent") boolean defaultPresent,
                     @JsonProperty("deprecated") boolean deprecated,
                     @JsonProperty("replacement") String replacement) {
    }

    record EnvVar(@JsonProperty("key") String key,
                  @JsonProperty("component") String component,
                  @JsonProperty("type") String type,
                  @JsonProperty("defaultValue") String defaultValue,
                  @JsonProperty("sensitive") boolean sensitive,
                  @JsonProperty("defaultPresent") boolean defaultPresent,
                  @JsonProperty("deprecated") boolean deprecated,
                  @JsonProperty("replacement") String replacement) {
    }

    record FrozenConfig(@JsonProperty("configKeys") List<ConfigKey> configKeys,
                         @JsonProperty("envVars") List<EnvVar> envVars) {
    }

    // --- 3.4 Agent protocol: capabilities + actual envelope/ack JSON ---
    record FrozenProtocol(@JsonProperty("protocolVersions") List<String> protocolVersions,
                          @JsonProperty("capabilities") List<String> capabilities,
                          @JsonProperty("commandEnvelopeJson") String commandEnvelopeJson,
                          @JsonProperty("ackedAckJson") String ackedAckJson,
                          @JsonProperty("failedAckJson") String failedAckJson,
                          @JsonProperty("capabilityFailureAckJson") String capabilityFailureAckJson) {
    }

    // --- 3.1 normalized OpenAPI (semantic) ---
    // additionalProperties contract (shared by PropertySnapshot and SchemaSnapshot):
    //   null                -> unset (open: any extra property accepted; OpenAPI default)
    //   "true"              -> explicitly open (additionalProperties: true)
    //   "false"             -> closed (additionalProperties: false; no extra properties allowed)
    //   "schema:<json>"     -> object-valued (typed) map: the full normalized value schema with
    //                          stable leaf refs (type/format/enum/nullable/required/properties/
    //                          items/constraints/composition), so the value shape is fully preserved
    //                          rather than reduced to a bare type.
    // Frozen as a single string so the comparator can classify openness and reject narrowing
    // (open/true -> false, open -> typed, typed -> closed) while allowing safe relaxations
    // (false -> true/unset, typed -> open). For two typed contracts the comparator recurses over
    // the full value schema, rejecting type/ref/format/constraint mutation, enum narrowing,
    // required additions and property removal, and allowing enum widening, nullable relaxation
    // and additive optional properties.
    record ParamSnapshot(@JsonProperty("name") String name,
                         @JsonProperty("in") String in,
                         @JsonProperty("required") boolean required,
                         @JsonProperty("type") String type,
                         @JsonProperty("format") String format,
                         @JsonProperty("nullable") boolean nullable,
                         @JsonProperty("schemaRef") String schemaRef,
                         @JsonProperty("enum") List<String> enumValues,
                         @JsonProperty("schemaContract") String schemaContract) {
    }

    record OperationSnapshot(@JsonProperty("method") String method,
                             @JsonProperty("path") String path,
                             @JsonProperty("deprecated") boolean deprecated,
                             @JsonProperty("replacement") String replacement,
                             @JsonProperty("removalVersion") String removalVersion,
                             @JsonProperty("authorization") String authorization,
                             @JsonProperty("security") List<String> security,
                             @JsonProperty("parameters") List<ParamSnapshot> parameters,
                             @JsonProperty("requestBodyRequired") boolean requestBodyRequired,
                             @JsonProperty("requestBodySchema") String requestBodySchema,
                             @JsonProperty("requestContentTypes") List<String> requestContentTypes,
                             @JsonProperty("responses") List<ResponseSnapshot> responses) {
    }

    record ResponseSnapshot(@JsonProperty("status") String status,
                            @JsonProperty("schema") String schema,
                            @JsonProperty("contentTypes") List<String> contentTypes) {
    }

    record PropertySnapshot(@JsonProperty("name") String name,
                            @JsonProperty("type") String type,
                            @JsonProperty("format") String format,
                            @JsonProperty("schemaRef") String schemaRef,
                            @JsonProperty("nullable") boolean nullable,
                            @JsonProperty("enum") List<String> enumValues,
                            @JsonProperty("itemsType") String itemsType,
                            @JsonProperty("itemsRef") String itemsRef,
                            @JsonProperty("additionalProperties") String additionalProperties) {
    }

    record SchemaSnapshot(@JsonProperty("name") String name,
                          @JsonProperty("type") String type,
                          @JsonProperty("format") String format,
                          @JsonProperty("required") List<String> required,
                          @JsonProperty("properties") List<PropertySnapshot> properties,
                          @JsonProperty("composition") List<String> composition,
                          @JsonProperty("additionalProperties") String additionalProperties) {
    }

    record SecuritySchemeSnapshot(@JsonProperty("name") String name,
                                  @JsonProperty("type") String type,
                                  @JsonProperty("scheme") String scheme,
                                  @JsonProperty("bearerFormat") String bearerFormat) {
    }

    record NormalizedApi(@JsonProperty("operations") List<OperationSnapshot> operations,
                         @JsonProperty("schemas") List<SchemaSnapshot> schemas,
                         @JsonProperty("securitySchemes") List<SecuritySchemeSnapshot> securitySchemes) {
    }

    // --- 3.1 error codes (the production KairoErrorCatalog is the source of truth) ---
    record ErrorDef(@JsonProperty("code") String code,
                    @JsonProperty("category") String category,
                    @JsonProperty("httpStatus") int httpStatus,
                    @JsonProperty("retryable") boolean retryable) {
    }

    record ErrorCatalog(@JsonProperty("codes") List<ErrorDef> codes) {
    }
}
