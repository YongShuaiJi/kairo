package com.example.kairo.agent.core;

import java.util.List;

/**
 * Raised when a by-name target resolution matches more than one loaded class
 * across distinct ClassLoaders and the caller did not request an explicit
 * all-match (V1.5 &sect;4.1 / &sect;5: "同名候选多于一个时 API 返回
 * AMBIGUOUS_TARGET，除非调用者显式 all-match").
 *
 * <p>Carries the candidate loader ids so the platform can surface them and the
 * caller can re-issue with the exact classLoaderId it meant. The agent never
 * silently picks the first candidate: an ambiguous match is a refusal.
 */
public final class AmbiguousTargetException extends IllegalArgumentException {

    private final String className;
    private final List<String> candidateLoaderIds;

    public AmbiguousTargetException(String className, List<String> candidateLoaderIds) {
        super("Ambiguous target '" + className + "': matched " + candidateLoaderIds.size()
                + " ClassLoaders " + candidateLoaderIds
                + "; provide an explicit classLoaderId or request all-match");
        this.className = className;
        this.candidateLoaderIds = List.copyOf(candidateLoaderIds);
    }

    public String className() {
        return className;
    }

    public List<String> candidateLoaderIds() {
        return candidateLoaderIds;
    }
}
