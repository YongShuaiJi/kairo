package com.example.kairo.platform.freeze;

import com.example.kairo.api.error.ErrorCategory;
import com.example.kairo.api.error.KairoErrorCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the error contract from the immutable V1.6 source tree. The production catalog remains
 * the semantic source of truth for current code; this historical extractor is a lineage guard that
 * prevents a current/additive V1.7 code from being written into the V1.6 compatibility floor.
 */
final class HistoricalErrorCatalog {

    private static final Pattern FACTORY = Pattern.compile(
            "PlatformException\\.(badRequest|conflict|methodNotAllowed|rateLimited)"
                    + "\\s*\\(\\s*\"([A-Z][A-Z0-9_]*)\"", Pattern.DOTALL);
    private static final Pattern FORBIDDEN_WITH_CODE = Pattern.compile(
            "PlatformException\\.forbidden\\s*\\(\\s*\"([A-Z][A-Z0-9_]*)\"\\s*,",
            Pattern.DOTALL);
    private static final Pattern UNAUTHORIZED_WITH_CODE = Pattern.compile(
            "PlatformException\\.unauthorized\\s*\\(\\s*\"([A-Z][A-Z0-9_]*)\"\\s*,",
            Pattern.DOTALL);
    private static final Pattern API_ERROR = Pattern.compile(
            "ApiError\\.of\\s*\\(\\s*\"([A-Z][A-Z0-9_]*)\"", Pattern.DOTALL);
    private static final Pattern IDEMPOTENCY_WRITE_ERROR = Pattern.compile(
            "writeError\\s*\\(\\s*response\\s*,\\s*409\\s*,\\s*"
                    + "\"(IDEMPOTENCY_KEY_CONFLICT)\"", Pattern.DOTALL);

    private HistoricalErrorCatalog() {
    }

    static FreezeModels.ErrorCatalog fromSourceRoot(Path sourceRoot) throws IOException {
        Path javaRoot = sourceRoot.resolve("kairo-platform-server/src/main/java");
        List<String> sources = new ArrayList<>();
        try (var paths = Files.walk(javaRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.add(Files.readString(path));
            }
        }
        return fromSources(sources);
    }

    static FreezeModels.ErrorCatalog fromSources(Iterable<String> sources) {
        Map<String, FreezeModels.ErrorDef> discovered = new LinkedHashMap<>();
        for (String source : sources) {
            Matcher factory = FACTORY.matcher(source);
            while (factory.find()) {
                put(discovered, definition(factory.group(2), factory.group(1)));
            }
            addMatches(discovered, source, FORBIDDEN_WITH_CODE,
                    ErrorCategory.AUTHORIZATION, 403, false);
            addMatches(discovered, source, UNAUTHORIZED_WITH_CODE,
                    ErrorCategory.AUTHENTICATION, 401, false);
            Matcher direct = API_ERROR.matcher(source);
            while (direct.find()) {
                put(discovered, directDefinition(direct.group(1)));
            }
            addMatches(discovered, source, IDEMPOTENCY_WRITE_ERROR,
                    ErrorCategory.CONFLICT, 409, false);
        }

        // Stable codes synthesized by V1.6 factories rather than supplied as call-site literals.
        put(discovered, def("FORBIDDEN", ErrorCategory.AUTHORIZATION, 403, false));
        put(discovered, def("UNAUTHORIZED", ErrorCategory.AUTHENTICATION, 401, false));
        put(discovered, def("RESOURCE_NOT_FOUND", ErrorCategory.NOT_FOUND, 404, false));
        put(discovered, def("CAPABILITY_NOT_SUPPORTED", ErrorCategory.CAPABILITY, 409, false));

        List<FreezeModels.ErrorDef> result = discovered.values().stream()
                .sorted(java.util.Comparator.comparing(FreezeModels.ErrorDef::code)).toList();
        return new FreezeModels.ErrorCatalog(result);
    }

    static void assertRepresentedByCurrentCatalog(FreezeModels.ErrorCatalog historical) {
        for (FreezeModels.ErrorDef frozen : historical.codes()) {
            KairoErrorCatalog.Entry current = KairoErrorCatalog.require(frozen.code());
            if (!current.category().name().equals(frozen.category())
                    || current.httpStatus() != frozen.httpStatus()
                    || current.retryable() != frozen.retryable()) {
                throw new IllegalStateException("V1.6 error metadata changed for " + frozen.code()
                        + ": historical=" + frozen + ", current=" + current);
            }
        }
    }

    private static FreezeModels.ErrorDef definition(String code, String factory) {
        return switch (factory) {
            case "badRequest" -> def(code, ErrorCategory.VALIDATION, 400, false);
            case "conflict" -> def(code, ErrorCategory.CONFLICT, 409, true);
            case "methodNotAllowed" -> def(code, ErrorCategory.VALIDATION, 405, false);
            case "rateLimited" -> def(code, ErrorCategory.RATE_LIMITED, 429, true);
            default -> throw new IllegalArgumentException("Unknown factory: " + factory);
        };
    }

    private static FreezeModels.ErrorDef directDefinition(String code) {
        return switch (code) {
            case "VALIDATION_FAILED" -> def(code, ErrorCategory.VALIDATION, 400, false);
            case "ROUTE_NOT_FOUND" -> def(code, ErrorCategory.NOT_FOUND, 404, false);
            case "INTERNAL_ERROR" -> def(code, ErrorCategory.INTERNAL, 500, false);
            case "IDEMPOTENCY_KEY_IN_PROGRESS" -> def(code, ErrorCategory.CONFLICT, 409, true);
            default -> throw new IllegalStateException(
                    "Unclassified direct V1.6 ApiError.of code: " + code);
        };
    }

    private static void addMatches(Map<String, FreezeModels.ErrorDef> out, String source,
                                   Pattern pattern, ErrorCategory category, int status,
                                   boolean retryable) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            put(out, def(matcher.group(1), category, status, retryable));
        }
    }

    private static FreezeModels.ErrorDef def(String code, ErrorCategory category, int status,
                                              boolean retryable) {
        return new FreezeModels.ErrorDef(code, category.name(), status, retryable);
    }

    private static void put(Map<String, FreezeModels.ErrorDef> out,
                            FreezeModels.ErrorDef definition) {
        FreezeModels.ErrorDef previous = out.putIfAbsent(definition.code(), definition);
        if (previous != null && !previous.equals(definition)) {
            throw new IllegalStateException("V1.6 error code has conflicting metadata: "
                    + previous + " vs " + definition);
        }
    }
}
