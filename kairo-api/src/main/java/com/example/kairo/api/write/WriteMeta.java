package com.example.kairo.api.write;

import java.util.Objects;

/**
 * Write-operation metadata extracted from request headers and body
 * (V1.6 &sect;2.3). Centralises the write protocol so every write endpoint applies
 * idempotency, optimistic locking, correlation, actor/source and dry-run uniformly.
 *
 * @param idempotencyKey  {@code Idempotency-Key} header
 * @param expectedVersion {@code If-Match} / body {@code expectedVersion}; null when unversioned
 * @param correlationId   {@code X-Correlation-Id} header
 * @param actor           authenticated subject id
 * @param source          origin, e.g. {@code web}, {@code cli}, {@code sdk}, {@code mcp}
 * @param dryRun          {@code true} when the caller requested a no-side-effect preview
 */
public record WriteMeta(
        String idempotencyKey,
        Long expectedVersion,
        String correlationId,
        String actor,
        String source,
        boolean dryRun
) {
    public WriteMeta {
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
        correlationId = correlationId == null ? "" : correlationId;
        actor = actor == null ? "" : actor;
        source = source == null || source.isBlank() ? null : source;
        Objects.requireNonNull(expectedVersion == null ? 0L : expectedVersion, "expectedVersion");
    }
}
