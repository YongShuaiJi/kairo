package com.example.kairo.api.write;

import java.util.List;
import java.util.Objects;

/**
 * Structured impact range of a write operation (V1.6 &sect;2.3 "结构化影响范围").
 * Returned by preview and recorded on the Operation so callers (and AI clients)
 * can decide whether to proceed without parsing prose.
 *
 * @param affectedResources       resources that will be/ were mutated
 * @param scope                   human-readable scope, e.g. {@code app:checkout/env:prod}
 * @param blastRadius             {@code single-instance}, {@code multi-instance} or {@code global}
 * @param reversible              whether a revert path exists
 * @param estimatedAffectedInstances best-effort count of JVM instances affected
 */
public record ImpactSummary(
        List<AffectedResource> affectedResources,
        String scope,
        String blastRadius,
        boolean reversible,
        int estimatedAffectedInstances
) {
    public ImpactSummary {
        Objects.requireNonNull(affectedResources, "affectedResources");
        affectedResources = List.copyOf(affectedResources);
        scope = scope == null ? "" : scope;
        blastRadius = blastRadius == null ? "" : blastRadius;
    }

    public record AffectedResource(String resourceType, String resourceId) {
        public AffectedResource {
            Objects.requireNonNull(resourceType, "resourceType");
            Objects.requireNonNull(resourceId, "resourceId");
        }
    }
}
