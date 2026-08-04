package com.example.kairo.platform.freeze;

import com.example.kairo.agent.server.protocol.AgentProtocolInfo;
import com.example.kairo.api.config.KairoConfigCatalog;
import com.example.kairo.api.error.KairoErrorCatalog;
import com.example.kairo.api.protocol.AgentCommandAck;
import com.example.kairo.api.protocol.AgentCommandEnvelope;
import com.example.kairo.platform.api.KairoApiAuthorizationCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Collectors that build the "current" contract state from authoritative sources, shared by the
 * separate {@link FreezeBaselineGeneratorTest} (writes baselines from V1.6.0 {@code 113823b}) and
 * the compare-only gate tests.
 *
 * <p>Authoritative sources (no regex-over-Java-source; no DB columns as wire schema):
 * <ul>
 *   <li>migrations: committed SQL files + Flyway's own {@code flyway_schema_history} checksum;</li>
 *   <li>config: the production {@link KairoConfigCatalog} (with source coverage guards);</li>
 *   <li>protocol: {@link AgentProtocolInfo#defaultV1()} + the serialized JSON of the authoritative
 *       {@link AgentCommandEnvelope}/{@link AgentCommandAck} DTOs (the actual wire contract);</li>
 *   <li>OpenAPI: the live {@code /v3/api-docs}, normalized semantically;</li>
 *   <li>error codes: the production {@link KairoErrorCatalog} (the single source of truth).</li>
 * </ul>
 */
final class FreezeCollectors {

    static final String MIGRATION_PATTERN = "classpath:/db/migration/V*.sql";
    /** Sorted-key mapper so envelope/ack JSON is deterministic across runs (Map.of order is undefined). */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private FreezeCollectors() {
    }

    // ---------------- 3.2 migrations ----------------

    static FreezeModels.FrozenMigrations collectMigrations() throws Exception {
        return collectMigrations(null);
    }

    /** Collect migration bytes from an explicit historical checkout when supplied. */
    static FreezeModels.FrozenMigrations collectMigrations(Path sourceRoot) throws Exception {
        TreeMap<String, MigrationFile> files = new TreeMap<>();
        if (sourceRoot == null) {
            for (org.springframework.core.io.Resource r : new org.springframework.core.io.support
                    .PathMatchingResourcePatternResolver().getResources(MIGRATION_PATTERN)) {
                String name = r.getFilename();
                if (name != null) {
                    addMigration(files, name, r.getInputStream().readAllBytes());
                }
            }
        } else {
            Path migrationDir = sourceRoot.resolve(
                    "kairo-platform-server/src/main/resources/db/migration");
            try (var paths = Files.list(migrationDir)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches("V.*\\.sql"))
                        .toList()) {
                    addMigration(files, path.getFileName().toString(), Files.readAllBytes(path));
                }
            }
        }
        TreeMap<String, Integer> flywayByScript = flywayChecksumsByScript();
        List<FreezeModels.FrozenMigration> list = new ArrayList<>();
        for (var e : files.entrySet()) {
            Integer fc = flywayByScript.get(e.getKey());
            if (fc == null) {
                throw new IllegalStateException("Migration " + e.getKey()
                        + " was not applied by Flyway; cannot compute its authoritative checksum.");
            }
            MigrationFile mf = e.getValue();
            list.add(new FreezeModels.FrozenMigration(e.getKey(), mf.version, mf.sha256, fc, mf.lines));
        }
        return new FreezeModels.FrozenMigrations(list);
    }

    private static void addMigration(TreeMap<String, MigrationFile> files,
                                     String name, byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        files.put(name, new MigrationFile(parseVersion(name),
                HexFormat.of().formatHex(digest), countLines(bytes)));
    }

    private static TreeMap<String, Integer> flywayChecksumsByScript() throws Exception {
        DataSource ds = h2DataSource();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        TreeMap<String, Integer> byScript = new TreeMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select script, checksum from flyway_schema_history where type='SQL' order by installed_rank");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byScript.put(rs.getString("script"), rs.getInt("checksum"));
            }
        }
        return byScript;
    }

    private static DataSource h2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        // DB_CLOSE_DELAY=-1 keeps the in-memory DB alive across connections so the post-migrate
        // query against flyway_schema_history sees the migrated schema.
        ds.setURL("jdbc:h2:mem:kairo_freeze_mig_" + Thread.currentThread().getId()
                + "_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static String parseVersion(String file) {
        return file.substring(1, file.indexOf('_'));
    }

    private static int countLines(byte[] bytes) {
        int count = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                count++;
            }
        }
        return count;
    }

    private record MigrationFile(String version, String sha256, int lines) {
    }

    // ---------------- 3.3 config ----------------

    static FreezeModels.FrozenConfig collectConfig() {
        List<FreezeModels.ConfigKey> keys = new ArrayList<>();
        List<FreezeModels.EnvVar> env = new ArrayList<>();
        for (KairoConfigCatalog.Binding binding : KairoConfigCatalog.entries()) {
            if (binding.channel() == KairoConfigCatalog.Channel.SPRING_PROPERTY) {
                keys.add(new FreezeModels.ConfigKey(binding.key(), binding.component(),
                        normalizedType(binding.type()), binding.defaultValue(), binding.sensitive(),
                        binding.defaultPresent(), binding.deprecated(), binding.replacement()));
            } else {
                env.add(new FreezeModels.EnvVar(binding.key(), binding.component(),
                        normalizedType(binding.type()), binding.defaultValue(), binding.sensitive(),
                        binding.defaultPresent(), binding.deprecated(), binding.replacement()));
            }
        }
        java.util.Comparator<FreezeModels.ConfigKey> keyOrder = java.util.Comparator
                .comparing(FreezeModels.ConfigKey::component).thenComparing(FreezeModels.ConfigKey::key);
        java.util.Comparator<FreezeModels.EnvVar> envOrder = java.util.Comparator
                .comparing(FreezeModels.EnvVar::component).thenComparing(FreezeModels.EnvVar::key);
        keys.sort(keyOrder);
        env.sort(envOrder);
        return new FreezeModels.FrozenConfig(keys, env);
    }

    private static String normalizedType(KairoConfigCatalog.ValueType type) {
        return switch (type) {
            case BOOLEAN -> "boolean";
            case INTEGER, LONG -> "number";
            case STRING, URL, JSON -> "string";
        };
    }

    // ---------------- 3.4 protocol: capabilities + actual envelope/ack JSON ----------------

    static FreezeModels.FrozenProtocol collectProtocol() throws Exception {
        AgentProtocolInfo info = AgentProtocolInfo.defaultV1();
        return collectProtocol(info.protocolVersions(), info.capabilities());
    }

    static FreezeModels.FrozenProtocol collectProtocol(
            java.util.Collection<String> protocolVersions,
            java.util.Collection<String> advertisedCapabilities) throws Exception {
        List<String> versions = new ArrayList<>(protocolVersions);
        List<String> capabilities = new ArrayList<>(new TreeSet<>(advertisedCapabilities));
        String envelopeJson = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(AgentCommandEnvelope.representative());
        String ackedAckJson = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(AgentCommandAck.ackedRepresentative());
        String failedAckJson = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(AgentCommandAck.failedRepresentative());
        String capabilityFailureAckJson = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(AgentCommandAck.capabilityFailureRepresentative());
        return new FreezeModels.FrozenProtocol(versions, capabilities, envelopeJson,
                ackedAckJson, failedAckJson, capabilityFailureAckJson);
    }

    // ---------------- 3.1 error codes: the production catalog ----------------

    static FreezeModels.ErrorCatalog collectErrorCatalog() {
        List<FreezeModels.ErrorDef> codes = new ArrayList<>();
        for (KairoErrorCatalog.Entry e : KairoErrorCatalog.entries()) {
            codes.add(new FreezeModels.ErrorDef(e.code(), e.category().name(),
                    e.httpStatus(), e.retryable()));
        }
        codes.sort(java.util.Comparator.comparing(FreezeModels.ErrorDef::code));
        return new FreezeModels.ErrorCatalog(codes);
    }

    // ---------------- 3.1 normalized OpenAPI (semantic) ----------------

    static FreezeModels.NormalizedApi normalizeOpenApi(JsonNode doc) {
        List<FreezeModels.OperationSnapshot> operations = new ArrayList<>();
        Set<String> methods = Set.of("get", "post", "put", "delete", "patch", "options", "head");
        JsonNode paths = doc.path("paths");
        if (paths.isObject()) {
            List<String> pathNames = sortedFields(paths);
            for (String path : pathNames) {
                JsonNode item = paths.path(path);
                if (!item.isObject()) {
                    continue;
                }
                for (String method : sortedFields(item)) {
                    if (!methods.contains(method.toLowerCase())) {
                        continue;
                    }
                    JsonNode op = item.path(method);
                    JsonNode operationSecurity = op.has("security")
                            ? op.path("security") : doc.path("security");
                    JsonNode requestContent = op.path("requestBody").path("content");
                    operations.add(new FreezeModels.OperationSnapshot(
                            method.toLowerCase(), path,
                            op.path("deprecated").asBoolean(false),
                            op.path("x-kairo-replacement").asText(""),
                            op.path("x-kairo-removal-version").asText(""),
                            op.path(KairoApiAuthorizationCatalog.EXTENSION)
                                    .asText(KairoApiAuthorizationCatalog.AUTHENTICATED),
                            securityRequirements(operationSecurity),
                            parameters(op),
                            op.path("requestBody").path("required").asBoolean(false),
                            contentSchemaContract(requestContent),
                            requestContent.isObject() ? sortedFields(requestContent) : List.of(),
                            responses(op.path("responses"))));
                }
            }
        }
        List<FreezeModels.SecuritySchemeSnapshot> schemes = new ArrayList<>();
        JsonNode compSchemes = doc.path("components").path("securitySchemes");
        if (compSchemes.isObject()) {
            for (String name : sortedFields(compSchemes)) {
                JsonNode s = compSchemes.path(name);
                schemes.add(new FreezeModels.SecuritySchemeSnapshot(name,
                        s.path("type").asText(null), s.path("scheme").asText(null),
                        s.path("bearerFormat").asText(null)));
            }
        }
        List<FreezeModels.SchemaSnapshot> schemas = new ArrayList<>();
        JsonNode compSchemas = doc.path("components").path("schemas");
        if (compSchemas.isObject()) {
            for (String name : sortedFields(compSchemas)) {
                schemas.add(schemaSnapshot(name, compSchemas.path(name)));
            }
        }
        return new FreezeModels.NormalizedApi(operations, schemas, schemes);
    }

    /** Add the audited V1.6 authorization contract to a historical raw OpenAPI document. */
    static JsonNode enrichAuthorization(JsonNode doc) {
        JsonNode paths = doc.path("paths");
        Set<String> methods = Set.of("get", "post", "put", "delete", "patch", "options", "head");
        if (paths.isObject()) {
            for (String path : sortedFields(paths)) {
                JsonNode item = paths.path(path);
                if (!item.isObject()) {
                    continue;
                }
                for (String method : sortedFields(item)) {
                    JsonNode operation = item.path(method);
                    if (methods.contains(method.toLowerCase()) && operation.isObject()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) operation).put(
                                KairoApiAuthorizationCatalog.EXTENSION,
                                KairoApiAuthorizationCatalog.requirement(method, path));
                    }
                }
            }
        }
        return doc;
    }

    private static FreezeModels.SchemaSnapshot schemaSnapshot(String name, JsonNode s) {
        List<String> required = new ArrayList<>();
        JsonNode req = s.path("required");
        if (req.isArray()) {
            req.forEach(n -> required.add(n.asText()));
        }
        required.sort(null);
        List<FreezeModels.PropertySnapshot> props = new ArrayList<>();
        JsonNode propsNode = s.path("properties");
        if (propsNode.isObject()) {
            for (String propName : sortedFields(propsNode)) {
                props.add(propertySnapshot(propName, propsNode.path(propName)));
            }
        }
        List<String> composition = new ArrayList<>();
        for (String kind : new String[]{"allOf", "anyOf", "oneOf"}) {
            JsonNode node = s.path(kind);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    String ref = item.path("$ref").asText(null);
                    if (ref != null) {
                        composition.add(kind + ":" + leafRef(ref));
                    }
                }
            }
        }
        return new FreezeModels.SchemaSnapshot(name,
                s.path("type").asText("object"), s.path("format").asText(null),
                required, props, composition, additionalPropertiesContract(s));
    }

    private static FreezeModels.PropertySnapshot propertySnapshot(String name, JsonNode p) {
        String type = p.path("type").asText(null);
        String ref = p.path("$ref").asText(null);
        if (type == null && ref != null) {
            type = "$ref";
        }
        if (ref != null) {
            ref = leafRef(ref);
        }
        JsonNode en = p.path("enum");
        List<String> enumValues = en.isArray() ? toStringList(en) : null;
        JsonNode items = p.path("items");
        String itemsType = items.path("type").asText(null);
        String itemsRef = items.path("$ref").asText(null);
        if (itemsRef != null) {
            itemsRef = leafRef(itemsRef);
        }
        String addl = additionalPropertiesContract(p);
        return new FreezeModels.PropertySnapshot(name, type, p.path("format").asText(null), ref,
                p.path("nullable").asBoolean(false), enumValues, itemsType, itemsRef, addl);
    }

    /**
     * Canonical additionalProperties contract for a schema or property: {@code null} (unset/open),
     * {@code "true"} (open), {@code "false"} (closed), or {@code "schema:<normalized json>"} for an
     * object-valued (typed) map. The normalized value schema preserves the <em>full</em> shape --
     * {@code $ref} (as a stable leaf ref), {@code type}, {@code format}, {@code enum},
     * {@code nullable}, {@code required}, nested {@code properties}/{@code items}, constraints and
     * composition -- so the comparator can distinguish openness from a concrete value schema, reject
     * narrowing, and recurse over the value shape rather than compare an opaque string.
     *
     * <p>This replaces the earlier {@code "type:<type>"} / {@code "ref:<leaf>"} shortcuts, which
     * discarded everything except the bare type and let a {@code {type:string,format:uuid} ->
     * {type:string,format:date}} (or enum narrowing, nested-property removal, constraint tightening)
     * mutation pass undetected.
     */
    private static String additionalPropertiesContract(JsonNode parent) {
        JsonNode ap = parent.path("additionalProperties");
        if (ap.isMissingNode() || ap.isNull()) {
            return null;
        }
        if (ap.isBoolean()) {
            return ap.asText();
        }
        // An additionalProperties schema that declares neither a $ref nor a (non-empty) type
        // constrains nothing -- it permits any value, so it is semantically OPEN, equivalent to
        // unset/true. swagger-core 2.2.47 (pulled in transitively by springdoc 2.8.17, which is
        // required for Spring Boot 3.5.16 compatibility) renders Object-typed map values as {}
        // (open) where 2.2.22 rendered {type:object}; recognizing the unconstrained form as OPEN
        // lets the additive-only comparator classify that typed->open rendering change as the safe
        // relaxation it is, rather than a typed shape mutation. Narrowing transitions (open->typed,
        // open->closed, typed->closed, typed shape/ref mutation) are still rejected downstream.
        if (!hasRefOrType(ap)) {
            return null;
        }
        return "schema:" + schemaShape(ap);
    }

    /** A schema with a $ref or a non-empty {@code type} is a concrete (typed) value schema, not open. */
    private static boolean hasRefOrType(JsonNode schema) {
        String ref = schema.path("$ref").asText(null);
        if (ref != null && !ref.isEmpty()) {
            return true;
        }
        String type = schema.path("type").asText(null);
        return type != null && !type.isEmpty();
    }

    private static String schemaShape(JsonNode schema) {
        try {
            return JSON.writeValueAsString(normalizeSchemaNode(schema));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize additionalProperties shape", e);
        }
    }

    private static List<FreezeModels.ParamSnapshot> parameters(JsonNode op) {
        List<FreezeModels.ParamSnapshot> params = new ArrayList<>();
        JsonNode arr = op.path("parameters");
        if (arr.isArray()) {
            for (JsonNode p : arr) {
                JsonNode schema = p.path("schema");
                String ref = schemaRef(schema);
                List<String> en = schema.path("enum").isArray() ? toStringList(schema.path("enum")) : null;
                params.add(new FreezeModels.ParamSnapshot(
                        p.path("name").asText(), p.path("in").asText(),
                        p.path("required").asBoolean(false),
                        schema.path("type").asText(null), schema.path("format").asText(null),
                        schema.path("nullable").asBoolean(false), ref, en,
                        schemaNodeContract(schema)));
            }
        }
        params.sort(java.util.Comparator.comparing(FreezeModels.ParamSnapshot::name,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        return params;
    }

    private static List<FreezeModels.ResponseSnapshot> responses(JsonNode responses) {
        List<FreezeModels.ResponseSnapshot> out = new ArrayList<>();
        if (responses.isObject()) {
            for (String status : sortedFields(responses)) {
                JsonNode content = responses.path(status).path("content");
                out.add(new FreezeModels.ResponseSnapshot(status,
                        contentSchemaContract(content),
                        content.isObject() ? sortedFields(content) : List.of()));
            }
        }
        out.sort(java.util.Comparator.comparing(FreezeModels.ResponseSnapshot::status));
        return out;
    }

    private static List<String> securityRequirements(JsonNode securityNode) {
        List<String> requirements = new ArrayList<>();
        if (securityNode.isArray()) {
            for (JsonNode req : securityNode) {
                if (req.isObject()) {
                    List<String> schemes = new ArrayList<>();
                    for (String scheme : sortedFields(req)) {
                        JsonNode scopeNode = req.path(scheme);
                        List<String> scopes = scopeNode.isArray() ? toStringList(scopeNode) : List.of();
                        schemes.add(scheme + "[" + String.join(",", scopes) + "]");
                    }
                    requirements.add(String.join("+", schemes));
                }
            }
        }
        requirements.sort(null);
        return requirements;
    }

    private static String schemaRef(JsonNode schema) {
        if (schema == null || schema.isMissingNode()) {
            return null;
        }
        String ref = schema.path("$ref").asText(null);
        if (ref != null) {
            return leafRef(ref);
        }
        return schema.path("type").asText("object");
    }

    /**
     * Canonical recursive schema contract for every media type of an operation body. Unlike the
     * old leaf-ref/type shortcut, this preserves inline objects, arrays, required fields, enums,
     * compositions and constraints so an inline request/response cannot change invisibly.
     */
    private static String contentSchemaContract(JsonNode content) {
        if (content == null || !content.isObject() || content.isEmpty()) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ObjectNode normalized = JSON.createObjectNode();
        for (String mediaType : sortedFields(content)) {
            JsonNode schema = content.path(mediaType).path("schema");
            if (!schema.isMissingNode() && !schema.isNull()) {
                normalized.set(mediaType, normalizeSchemaNode(schema));
            }
        }
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(normalized);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize normalized OpenAPI body schema", e);
        }
    }

    private static String schemaNodeContract(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(normalizeSchemaNode(schema));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize normalized OpenAPI parameter schema", e);
        }
    }

    private static JsonNode normalizeSchemaNode(JsonNode schema) {
        com.fasterxml.jackson.databind.node.ObjectNode out = JSON.createObjectNode();
        String ref = schema.path("$ref").asText(null);
        if (ref != null) {
            out.put("$ref", leafRef(ref));
        }
        for (String field : new String[]{"type", "format", "pattern", "default", "multipleOf",
                "minimum", "maximum", "const", "uniqueItems", "readOnly", "writeOnly",
                "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength",
                "minItems", "maxItems", "minProperties", "maxProperties",
                "minContains", "maxContains", "contentEncoding", "contentMediaType"}) {
            JsonNode value = schema.get(field);
            if (value != null && !value.isNull()) {
                out.set(field, value.deepCopy());
            }
        }
        if (schema.has("nullable")) {
            out.put("nullable", schema.path("nullable").asBoolean(false));
        }
        if (schema.path("enum").isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode values = JSON.createArrayNode();
            toStringList(schema.path("enum")).forEach(values::add);
            out.set("enum", values);
        }
        if (schema.path("required").isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode required = JSON.createArrayNode();
            toStringList(schema.path("required")).forEach(required::add);
            out.set("required", required);
        }
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode normalizedProperties = JSON.createObjectNode();
            for (String name : sortedFields(properties)) {
                normalizedProperties.set(name, normalizeSchemaNode(properties.path(name)));
            }
            out.set("properties", normalizedProperties);
        }
        if (schema.has("items") && schema.path("items").isObject()) {
            out.set("items", normalizeSchemaNode(schema.path("items")));
        }
        if (schema.has("additionalProperties")) {
            JsonNode additional = schema.path("additionalProperties");
            out.set("additionalProperties", additional.isObject()
                    ? normalizeSchemaNode(additional) : additional.deepCopy());
        }
        for (String kind : new String[]{"allOf", "anyOf", "oneOf"}) {
            if (schema.path(kind).isArray()) {
                com.fasterxml.jackson.databind.node.ArrayNode composition = JSON.createArrayNode();
                schema.path(kind).forEach(item -> composition.add(normalizeSchemaNode(item)));
                out.set(kind, composition);
            }
        }
        if (schema.path("not").isObject()) {
            out.set("not", normalizeSchemaNode(schema.path("not")));
        }
        return out;
    }

    private static String leafRef(String ref) {
        int idx = ref.lastIndexOf('/');
        return idx >= 0 ? ref.substring(idx + 1) : ref;
    }

    private static List<String> toStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        out.sort(null);
        return out;
    }

    private static List<String> sortedFields(JsonNode obj) {
        List<String> names = new ArrayList<>();
        obj.fieldNames().forEachRemaining(names::add);
        names.sort(null);
        return names;
    }

}
