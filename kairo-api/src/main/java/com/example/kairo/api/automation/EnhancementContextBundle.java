package com.example.kairo.api.automation;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.ConflictFinding;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.ProxyType;
import com.example.kairo.api.SupportLevel;

import java.util.List;
import java.util.Objects;

/**
 * The compact AI context package returned by {@code resolve-targets}
 * (V1.6 &sect;4.3). Gives a model exactly what it needs to choose a stable target
 * and author a correct script, with a hard size cap so huge class graphs are
 * never handed to the model wholesale.
 *
 * @param version              bundle schema version
 * @param sessionId            owning {@link AutomationSession}
 * @param candidates           ranked candidate targets with confidence and reason
 * @param classLoaders         class loaders observed on the target agent
 * @param enhancementLocations available locations and call sites per candidate
 * @param ruleChainConflicts   static conflicts against the current rule chain
 * @param scriptApiSurface     allowed tier, schema, examples and diagnostic format
 * @param sizeBytes            serialised size of this bundle, capped at {@link #MAX_SIZE_BYTES}
 * @param generatedAtMillis    epoch millis
 */
public record EnhancementContextBundle(
        int version,
        String sessionId,
        List<EnhancementCandidate> candidates,
        List<ClassLoaderSummary> classLoaders,
        List<EnhancementLocationOption> enhancementLocations,
        List<ConflictFinding> ruleChainConflicts,
        ScriptApiSurface scriptApiSurface,
        int sizeBytes,
        long generatedAtMillis
) {

    /** Hard upper bound on the serialised bundle size (V1.6 &sect;4.3 "大小上限"). */
    public static final int MAX_SIZE_BYTES = 256 * 1024;

    public EnhancementContextBundle {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be > 0");
        }
        sessionId = requireText(sessionId, "sessionId");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        classLoaders = classLoaders == null ? List.of() : List.copyOf(classLoaders);
        enhancementLocations = enhancementLocations == null ? List.of() : List.copyOf(enhancementLocations);
        ruleChainConflicts = ruleChainConflicts == null ? List.of() : List.copyOf(ruleChainConflicts);
        Objects.requireNonNull(scriptApiSurface, "scriptApiSurface");
        if (sizeBytes < 0) {
            sizeBytes = 0;
        }
        if (generatedAtMillis < 0) {
            generatedAtMillis = 0;
        }
    }

    /** Summary of a class loader visible to the target agent. */
    public record ClassLoaderSummary(
            String classLoaderId,
            SupportLevel supportLevel,
            ProxyType proxyType,
            String compatibilityNote
    ) {
        public ClassLoaderSummary {
            classLoaderId = requireText(classLoaderId, "classLoaderId");
            supportLevel = supportLevel == null ? SupportLevel.SUPPORTED : supportLevel;
            proxyType = proxyType == null ? ProxyType.PLAIN : proxyType;
            compatibilityNote = compatibilityNote == null ? "" : compatibilityNote;
        }
    }

    /** Available enhancement locations and call sites for one candidate. */
    public record EnhancementLocationOption(
            String targetId,
            List<EnhancementLocation> availableLocations,
            List<CallSiteSelector> callSites
    ) {
        public EnhancementLocationOption {
            targetId = requireText(targetId, "targetId");
            availableLocations = availableLocations == null ? List.of() : List.copyOf(availableLocations);
            callSites = callSites == null ? List.of() : List.copyOf(callSites);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
