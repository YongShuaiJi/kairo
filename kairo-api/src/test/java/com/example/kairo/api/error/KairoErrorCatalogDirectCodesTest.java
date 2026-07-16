package com.example.kairo.api.error;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M0: focused, deterministic assertions on the authoritative {@link KairoErrorCatalog} for
 * the four direct-{@code ApiError.of} codes added to the catalog (VALIDATION_FAILED,
 * ROUTE_NOT_FOUND, IDEMPOTENCY_KEY_CONFLICT, IDEMPOTENCY_KEY_IN_PROGRESS) and that the catalog's
 * emitted entry set is sorted by code (deterministic output).
 */
class KairoErrorCatalogDirectCodesTest {

    @Test
    void directApiErrorCodesHaveExactMetadata() {
        assertEntry("VALIDATION_FAILED", ErrorCategory.VALIDATION, 400, false);
        assertEntry("ROUTE_NOT_FOUND", ErrorCategory.NOT_FOUND, 404, false);
        assertEntry("IDEMPOTENCY_KEY_CONFLICT", ErrorCategory.CONFLICT, 409, false);
        assertEntry("IDEMPOTENCY_KEY_IN_PROGRESS", ErrorCategory.CONFLICT, 409, true);
    }

    @Test
    void catalogOutputIsSortedByCode() {
        List<KairoErrorCatalog.Entry> entries = new ArrayList<>(KairoErrorCatalog.entries());
        assertThat(entries).isNotEmpty();
        assertThat(entries).isEqualTo(entries.stream()
                .sorted(Comparator.comparing(KairoErrorCatalog.Entry::code)).toList());
    }

    @Test
    void codesAreUniqueAndResolvable() {
        List<String> codes = KairoErrorCatalog.codes().stream().sorted().toList();
        assertThat(codes).isEqualTo(codes.stream().distinct().toList());
        for (String code : List.of("VALIDATION_FAILED", "ROUTE_NOT_FOUND",
                "IDEMPOTENCY_KEY_CONFLICT", "IDEMPOTENCY_KEY_IN_PROGRESS")) {
            assertThat(KairoErrorCatalog.resolve(code)).as("resolvable: " + code).isNotNull();
        }
    }

    private static void assertEntry(String code, ErrorCategory category, int httpStatus,
                                    boolean retryable) {
        KairoErrorCatalog.Entry e = KairoErrorCatalog.require(code);
        assertThat(e.category()).as(code + " category").isEqualTo(category);
        assertThat(e.httpStatus()).as(code + " httpStatus").isEqualTo(httpStatus);
        assertThat(e.retryable()).as(code + " retryable").isEqualTo(retryable);
    }
}
